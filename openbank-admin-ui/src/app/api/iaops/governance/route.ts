// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { parse as parseYaml } from 'yaml'

export const dynamic = 'force-dynamic'

// Normalises a tools.allow / tools.deny list that may contain either flat strings
// (standard agents) or tier-object entries (e.g. finops-agent uses {tier, resources}).
function normalizeToolList(items: unknown[]): string[] {
  return items.flatMap(item => {
    if (typeof item === 'string') return [item]
    if (item && typeof item === 'object') {
      const o = item as Record<string, unknown>
      const tier = typeof o.tier === 'string' ? o.tier : ''
      const resources = Array.isArray(o.resources) ? (o.resources as unknown[]).map(String) : []
      return resources.length ? resources.map(r => `${tier}:${r}`) : tier ? [tier] : []
    }
    return []
  })
}

// ── AI governance snapshot for the IAOps section (ADR-0031) ──────────────────
//
// Two kinds of data, both grounded in repo sources (never fabricated):
//  1. CHARTERS — parsed live from the bundled agents.yaml (the machine-readable
//     source of truth, ADR-0031 D1). The image bakes it (Dockerfile COPY); the
//     route parses it so the UI can't drift from the policy the OPA gate uses.
//  2. DECISIONS / COMPLIANCE / AUDIT — the ADR-0031 plan and its compliance
//     surface. ADR-0031 is itself the source of truth for the roadmap, so we
//     encode D1–D9 + the regulatory mapping here with statuses reflecting the
//     CURRENT verifiable reality (built / partial / planned), not stale prose.
//
// If agents.yaml is absent (not bundled), charters degrade to an empty list with
// chartersAvailable:false — the static ADR content still renders. Never invents.

function agentsFile(): string {
  return process.env.OPENBANK_AGENTS_FILE
    ?? path.resolve(process.cwd(), 'agents.yaml')
}

interface ParsedAgents {
  defaults?: Record<string, unknown>
  runtime?: Record<string, unknown>
  model_gateway?: Record<string, unknown>
  tool_tiers?: Record<string, string[]>
  agents?: Record<string, unknown>[]
}

async function readCharters(): Promise<{ available: boolean; data: ParsedAgents | null }> {
  try {
    const raw = await fs.readFile(agentsFile(), 'utf-8')
    const data = parseYaml(raw) as ParsedAgents
    if (data && Array.isArray(data.agents)) return { available: true, data }
    return { available: false, data: null }
  } catch {
    return { available: false, data: null }
  }
}

// ADR-0031 D1–D9 with status reflecting current reality. Statuses verified
// against the codebase (agent-service, agents.yaml, agents.rego, AuditEvent):
//   built   = implemented + in the running system
//   partial = some parts built, key parts not yet wired/enforced
//   planned = scoped in the ADR, not yet built
type DStatus = 'built' | 'partial' | 'planned'
const DECISIONS: { id: string; title: string; status: DStatus; detail: string }[] = [
  { id: 'D1', title: 'Agents as code (agents.yaml + charter)', status: 'built',
    detail: 'agents.yaml is the machine-readable source of truth: per-agent plane, data scope, tool allow/deny, requires_human, limits.' },
  { id: 'D2', title: 'Policy-gated MCP (OPA before every tools/call)', status: 'built',
    detail: 'AgentPolicyGate enforces + audits every tool call. AGENT_POLICY_ENFORCEMENT=block in both the deployment and the agents.yaml declaration (reconciled #743), via an in-process charter allow-list (ADR-0080, #700) plus an OPA sidecar serving the agents.rego bundle over localhost:8181 as the external PDP (#638). Fails safe — a PDP connectivity error degrades to advisory.' },
  { id: 'D3', title: 'Verifiable agent identity (SPIFFE/SVID + CN cross-check)', status: 'built',
    detail: 'D3a + D3b live and enforcing. D3a: AgentIdentityBinding ties X-Agent-Id to operator\'s verified Keycloak roles (deny-by-default); rejected assertion audited (agent.identity.rejected) and discloses no tools. D3b (#2488/#2495): pki-agent OpenBao PKI engine issues short-TTL X.509 certs (CN=agent-id, 300s, no_store); McpEndpoint verifies chain against pki-agent CA + SHA256withECDSA proof-of-possession + CN→role cross-check (svid_cn_binding audit); AGENT_IDENTITY_SVID_ENFORCED=true (no fallback). E2E verified in sandbox: T1 no-SVID→0 tools, T2 CN=ui-assistant→12 tools, T3 CN=compliance-officer→0 tools + DENIED audit. Remaining: author≠approver GH branch protection rule (Free plan constraint, not a code gap).' },
  { id: 'D4', title: 'Human-in-the-loop (two channels)', status: 'built',
    detail: 'Development plane reuses GitHub PR + CODEOWNERS + branch protection (live for humans). Control-plane approval queue shipped in admin-ui (/approvals + /api/agent/proposals, #657): agent proposals are recorded for explicit human approval with segregation of duties (approver ≠ author).' },
  { id: 'D5', title: 'AI-attributed, tamper-evident audit', status: 'partial',
    detail: 'Every tool-call decision (agent.mcp.tool_call), tool-execution outcome (agent.mcp.tool_exec) and model completion emits an AuditEvent (actorType=AI_AGENT) with model_id, prompt_hash, policy_decision. Hash-chain live (audit_entries.record_hash/prev_hash, V5) + periodic externally-signed anchors (audit_anchor, V6): GET /api/v1/audit/anchors/verify detects wholesale rewrites; default signer is HMAC-SHA256 with key held outside the audit DB. Remaining: AI-attribution payload fields in the release evidence bundle and the production KMS/cosign-keyed anchor signer (asymmetric, third-party verifiable).' },
  { id: 'D6', title: 'Open, model-agnostic stack', status: 'partial',
    detail: 'Model-gateway port + OpenAI-compatible provider are built (provider-agnostic). Temporal, LangGraph, vLLM+LiteLLM, Langfuse, guardrails and pgvector RAG are planned.' },
  { id: 'D7', title: 'Observability, budgets, kill switch', status: 'partial',
    detail: 'Budget enforcement live (CharterRateLimiter: runs_per_day pre-flight + tokens_per_run). Kill switch live (KillSwitchService): config baseline (agents.yaml enabled / global_controls) + runtime break-glass (/api/v1/admin/agents), gate pre-flight DENY + audit. Per-run OTel tracing remains.' },
  { id: 'D8', title: 'Licensing & IP (AGPL agent runtime)', status: 'partial',
    detail: 'The Apache/AGPL seam is the ModelProvider port (demonstrated in PR #216). Licensed under Apache-2.0 repo-wide (ADR-0123, superseding ADR-0012). The separate AGPL-3.0 + CLA repo and the license_denylist carve-out are planned.' },
  { id: 'D9', title: 'Phasing (blast radius grows with controls)', status: 'partial',
    detail: 'Phase 1 controls live and ENFORCING (deny-by-default + block); the phase-2 HITL proposal queue is shipped. The first read-only oversight agent — HolmesGPT (rca-investigator, ADR-0088), which investigates alerts and proposes a root cause without acting — is now deployed, and the customer-facing copilot (ADR-0089) runs in its own SCA-gated regime (proposes; the bank disposes). No agent takes state-changing action yet. Phases 3–5 (dev agent → money-path → tamper-evidence) follow.' },
]

// Compliance surface — exactly ADR-0031 "Compliance impact". Phase-1 controls now
// ENFORCE (deny-by-default + block); statuses stay 'partial' where a framework also
// needs the still-planned pieces (live by-actor query, tamper-evidence, AI Act docs).
const COMPLIANCE: { framework: string; requirement: string; control: string; status: DStatus }[] = [
  { framework: 'PCI DSS', requirement: 'Req. 7 least privilege · Req. 10 audit trail',
    control: 'Per-agent tool gating (deny-by-default) + AI-attributed AuditEvent on every action.', status: 'partial' },
  { framework: 'DORA', requirement: 'Art. 8–10 ICT risk & change · Art. 17 incident reconstruction · Art. 28–30 third-party model provider',
    control: 'Charters as code, traceId on every run, model provider treated as ICT third party.', status: 'partial' },
  { framework: 'GDPR', requirement: 'Art. 30 records of processing · Art. 25/32 data protection',
    control: 'Agent actions in the audit log; PII masked on every agent data scope (PiiMasking).', status: 'built' },
  { framework: 'PSD2', requirement: 'SCA / consent stay human-gated',
    control: 'Agents may propose but never act on money-path; SCA/consent paths excluded from agent tools.', status: 'built' },
  { framework: 'CNB', requirement: 'Auditability & operational resilience of automated decisioning',
    control: 'Append-only audit + deny-by-default + kill switch (declared).', status: 'partial' },
  { framework: 'EU AI Act', requirement: 'Classified per agent · Art. 14 oversight · Art. 12 logging · Art. 13 transparency (Annex III high-risk only)',
    control: 'Oversight/dev agents are proposal-only (likely limited risk); HITL + AI-attributed audit cover any high-risk flow. No agent touches creditworthiness scoring.', status: 'partial' },
]

// What the AI audit trail actually captures + the pipeline (ADR-0031 D5).
const AUDIT_TRAIL = {
  capture: ['actorType=AI_AGENT', 'actorId (agent)', 'operation', 'model_id', 'model_version', 'prompt_hash', 'tool_calls[]', 'policy_decision (ALLOW/DENY)', 'result', 'traceId'],
  pipeline: ['agent-service emits AuditEvent', 'Kafka audit-events-out', 'audit-service append-only store', 'release evidence bundle (ai_attribution)'],
  live: ['Tool-call decisions audited (agent.mcp.tool_call)', 'Tool-execution outcomes audited (agent.mcp.tool_exec)', 'Model completions audited (agent.model.complete)', 'Audit envelope + Kafka pipeline', 'Hash-chain (record_hash/prev_hash, V5)', 'Externally-signed anchors (V6, HMAC-SHA256 key outside DB)'],
  planned: ['By-actor live query endpoint (audit-service)', 'Production KMS/cosign anchor signer (asymmetric, third-party verifiable)', 'ai_attribution populated in evidence bundle', 'Approval decisions (human_approver, reason)'],
}

export async function GET() {
  const charters = await readCharters()
  const d = charters.data

  // Posture: read live from agents.yaml defaults where possible.
  const defaults = (d?.defaults ?? {}) as Record<string, unknown>
  const enforced = typeof defaults.enforced === 'string' ? defaults.enforced : 'advisory'
  const policyDecision = typeof defaults.policy_decision === 'string' ? defaults.policy_decision : 'deny'

  const agents = (d?.agents ?? []).map(a => {
    const ag = a as Record<string, unknown>
    const tools = (ag.tools ?? {}) as Record<string, unknown>
    const limits = (ag.limits ?? {}) as Record<string, unknown>
    const dataScope = (ag.data_scope ?? {}) as Record<string, unknown>
    return {
      id: String(ag.id ?? 'unknown'),
      plane: String(ag.plane ?? '—'),
      charter: String(ag.charter ?? ''),
      owns: Array.isArray(ag.owns) ? (ag.owns as string[]) : [],
      skills: Array.isArray(ag.skills) ? (ag.skills as string[]) : [],
      dataRead: Array.isArray(dataScope.read) ? (dataScope.read as string[]) : [],
      pii: String(dataScope.pii ?? defaults.pii ?? 'masked'),
      toolsAllow: Array.isArray(tools.allow) ? normalizeToolList(tools.allow as unknown[]) : [],
      toolsDeny: Array.isArray(tools.deny) ? normalizeToolList(tools.deny as unknown[]) : [],
      requiresHuman: Array.isArray(ag.requires_human) ? (ag.requires_human as unknown[]).map(r =>
        typeof r === 'string' ? r : Object.entries(r as Record<string, unknown>).map(([k, v]) => `${k}: ${v}`).join(' ')) : [],
      tokensPerRun: typeof limits.tokens_per_run === 'number' ? limits.tokens_per_run : null,
      runsPerDay: typeof limits.runs_per_day === 'number' ? limits.runs_per_day : null,
    }
  })

  const builtCount = DECISIONS.filter(x => x.status === 'built').length
  const partialCount = DECISIONS.filter(x => x.status === 'partial').length
  const plannedCount = DECISIONS.filter(x => x.status === 'planned').length

  return NextResponse.json({
    adrRef: 'ADR-0031',
    adrStatus: 'Accepted',
    phase: 2,
    totalPhases: 5,
    phaseLabel: 'Read-only oversight active — HITL proposal queue live; HolmesGPT + copilot deployed (proposal-only, no autonomous state-changing action)',
    enforcement: enforced,            // enforced (block) since #743 — deny-by-default at the gate
    policyDefault: policyDecision,    // deny
    agentsActing: 0,                  // phase 1: no agent acts yet
    chartersAvailable: charters.available,
    agentCount: agents.length,
    agents,
    toolTiers: (d?.tool_tiers ?? {}) as Record<string, string[]>,
    runtime: (d?.runtime ?? {}) as Record<string, unknown>,
    modelGateway: (d?.model_gateway ?? {}) as Record<string, unknown>,
    decisions: DECISIONS,
    decisionSummary: { built: builtCount, partial: partialCount, planned: plannedCount, total: DECISIONS.length },
    compliance: COMPLIANCE,
    auditTrail: AUDIT_TRAIL,
  }, { headers: { 'Cache-Control': 'no-store' } })
}
