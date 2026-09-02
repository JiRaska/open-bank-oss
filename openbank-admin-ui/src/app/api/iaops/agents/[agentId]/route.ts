// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Per-agent drill-down BFF (ADR-0156). Merges three sources for the /iaops/agents/[agentId]
// detail page:
//  1. CHARTER — the enforced agents.yaml entry (lib/governance/agentCharters.ts, shared
//     with /api/iaops/governance so there is one parser).
//  2. NARRATIVE — the bundled docs/agents/<id>.md (lib/governance/docs.ts). Prose only.
//  3. PROPOSALS — this agent's HITL proposal history, proxied from agent-service with the
//     new `agentId` query filter (ProposalResource). Degrades gracefully (empty list, not
//     an error) if agent-service is unreachable — the charter + narrative still render.
//
// 404s only when the id matches neither agents.yaml nor a docs/agents/*.md file — an
// unknown agent, not a data-source outage.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { loadAgentCharters } from '@/lib/governance/agentCharters'
import { deriveAgentDiagnostics, deriveAgentMesh } from '@/lib/governance/agentDiagnostics'
import { loadAgentCharterDoc } from '@/lib/governance/docs'

export const dynamic = 'force-dynamic'

function agentBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://openbank-agent-service:8109'
  return (process.env.AGENT_SERVICE_URL ?? 'http://localhost:8109/mcp').replace(/\/mcp$/, '')
}

async function operatorBearer(): Promise<string | null> {
  return (await auth())?.user?.accessToken ?? null
}

interface ProposalSummary {
  id: string
  title: string
  state: string
  proposedAt: string
  decidedAt: string | null
}

async function fetchProposals(agentId: string): Promise<{ available: boolean; proposals: ProposalSummary[] }> {
  const accessToken = await operatorBearer()
  if (!accessToken) return { available: false, proposals: [] }
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 10000)
    const res = await fetch(`${agentBase()}/api/v1/proposals?state=all&agentId=${encodeURIComponent(agentId)}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      cache: 'no-store',
      signal: ctrl.signal,
    })
    clearTimeout(timer)
    if (!res.ok) return { available: false, proposals: [] }
    const rows = await res.json() as Array<Record<string, unknown>>
    return {
      available: true,
      // The query parameter narrows current agent-service versions. Keep this local
      // check as the BFF boundary too: an older service can silently ignore unknown
      // query parameters, and this page must never label another agent's work as
      // this agent's history during a rolling deployment.
      proposals: rows
        .filter(r => r.proposedBy === agentId)
        .map(r => ({
          id: String(r.id ?? ''),
          title: String(r.title ?? ''),
          state: String(r.state ?? ''),
          proposedAt: String(r.proposedAt ?? ''),
          decidedAt: r.decidedAt ? String(r.decidedAt) : null,
        })),
    }
  } catch {
    return { available: false, proposals: [] }
  }
}

export async function GET(_req: NextRequest, ctx: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await ctx.params
  const [registry, doc, proposalData] = await Promise.all([
    loadAgentCharters(),
    loadAgentCharterDoc(agentId),
    fetchProposals(agentId),
  ])
  const charter = registry.agents.find(agent => agent.id === agentId) ?? null

  if (!charter && !doc) {
    return NextResponse.json({ error: 'unknown_agent' }, { status: 404 })
  }

  return NextResponse.json({
    id: agentId,
    charter,
    diagnostics: charter ? deriveAgentDiagnostics(charter, registry.agents) : [],
    mesh: charter ? deriveAgentMesh(charter, registry) : null,
    narrative: doc ? { title: doc.title, adr: doc.adr, plane: doc.plane, body: doc.body } : null,
    proposals: {
      available: proposalData.available,
      items: proposalData.proposals,
      pendingCount: proposalData.proposals.filter(p => p.state === 'PROPOSED').length,
    },
  }, { headers: { 'Cache-Control': 'no-store' } })
}
