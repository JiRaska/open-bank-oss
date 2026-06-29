// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import {
  Bot, RefreshCw, ScrollText, GitBranch, Scale,
  Info, CheckCircle2, CircleDashed, CircleDot, Lock, Users, Search, Loader2,
  AlertOctagon,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { AgentInsightsPanel } from '@/components/agent/AgentInsightsPanel'
import type { AgentFinding } from '@/components/agent/AgentInsightsPanel'

// ── Types (mirror /api/iaops/governance) ───────────────────────────────────
type DStatus = 'built' | 'partial' | 'planned'

interface Agent {
  id: string; plane: string; charter: string; owns: string[]; skills: string[]
  dataRead: string[]; pii: string; toolsAllow: string[]; toolsDeny: string[]
  requiresHuman: string[]; tokensPerRun: number | null; runsPerDay: number | null
}
interface Decision { id: string; title: string; status: DStatus; detail: string }
interface Compliance { framework: string; requirement: string; control: string; status: DStatus }
interface GovData {
  adrRef: string; adrStatus: string; phase: number; totalPhases: number; phaseLabel: string
  enforcement: string; policyDefault: string; agentsActing: number
  chartersAvailable: boolean; agentCount: number; agents: Agent[]
  toolTiers: Record<string, string[]>
  decisions: Decision[]; decisionSummary: { built: number; partial: number; planned: number; total: number }
  compliance: Compliance[]
  auditTrail: { capture: string[]; pipeline: string[]; live: string[]; planned: string[] }
}

// Mirrors audit-service AnchorVerification (GET /api/v1/audit/anchors/verify, ADR-0031 D5).
interface AuditIntegrity {
  status: 'INTACT' | 'BROKEN'
  anchorCount: number
  verifiedCount: number
  unsignedCount: number
  firstBroken: { lastEntryId: string | null; signatureInvalid: boolean; headHashMismatch: boolean } | null
}

interface AgentCostEntry {
  agentId: string
  costLast24hUsd: number
  costLast7dUsd: number
  budgetMonthlyUsd: number | null
  budgetUsedPct: number | null
  burnRate: 'low' | 'normal' | 'high' | 'exceeded'
}

interface FinOpsAnomaly {
  id: string
  detectedAt: string
  detector: string
  severity: 'warning' | 'critical'
  title: string
  rootCause: string | null
  proposalPrUrl: string | null
  status: 'open' | 'proposed' | 'approved' | 'rejected' | 'resolved'
  estimatedMonthlySavingUsd: number | null
}

// Map a finops-agent anomaly → the shared AgentFinding view-model (admin-ui agent-output rule).
function toAgentFinding(a: FinOpsAnomaly, t: (cs: string, en: string) => string): AgentFinding {
  const tags: AgentFinding['tags'] = []
  if (a.estimatedMonthlySavingUsd != null) {
    tags.push({ label: t(`Úspora $${a.estimatedMonthlySavingUsd.toFixed(0)}/mo`, `Saves $${a.estimatedMonthlySavingUsd.toFixed(0)}/mo`), tone: 'success' })
  }
  return {
    id: a.id,
    title: a.title,
    detector: a.detector,
    severity: a.severity,
    status: a.status,
    rootCause: a.rootCause,
    detectedAt: a.detectedAt,
    proposalUrl: a.proposalPrUrl,
    proposalLabel: t('Zobrazit návrh →', 'View proposal →'),
    tags,
  }
}

// ── Status visual helpers ───────────────────────────────────────────────────
const STATUS_CFG: Record<DStatus, { color: string; bg: string; border: string; en: string; cs: string; icon: React.ReactNode }> = {
  built:   { color: '#16a34a', bg: '#dcfce7', border: '#86efac', en: 'Built',   cs: 'Hotovo',   icon: <CheckCircle2 size={13} /> },
  partial: { color: '#d97706', bg: '#fef9c3', border: '#fde047', en: 'Partial', cs: 'Částečně', icon: <CircleDot size={13} /> },
  planned: { color: '#6366f1', bg: '#ede9fe', border: '#c4b5fd', en: 'Planned', cs: 'Plánováno', icon: <CircleDashed size={13} /> },
}

function StatusPill({ status, large }: { status: DStatus; large?: boolean }) {
  const { language } = useLanguage()
  const c = STATUS_CFG[status]
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px',
      fontSize: large ? '12px' : '10px', fontWeight: 700, padding: large ? '3px 10px' : '2px 8px',
      borderRadius: '10px', color: c.color, background: c.bg, border: `1px solid ${c.border}`,
      letterSpacing: '0.04em', textTransform: 'uppercase' }}>
      {c.icon}{language === 'cs' ? c.cs : c.en}
    </span>
  )
}

function Card({ children, accent }: { children: React.ReactNode; accent?: string }) {
  return (
    <div style={{ background: 'var(--surface)', border: accent ? `1px solid ${accent}40` : '1px solid var(--border)',
      borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
      {children}
    </div>
  )
}

function SectionTitle({ icon, children, sub }: { icon: React.ReactNode; children: React.ReactNode; sub?: React.ReactNode }) {
  return (
    <div style={{ marginBottom: '16px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
        <span style={{ color: '#6366f1' }}>{icon}</span>
        <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{children}</span>
      </div>
      {sub && <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '6px 0 0' }}>{sub}</p>}
    </div>
  )
}

function Chips({ items, tone }: { items: string[]; tone: 'allow' | 'deny' | 'neutral' }) {
  const map = {
    allow:   { color: '#16a34a', bg: '#dcfce7' },
    deny:    { color: '#dc2626', bg: '#fee2e2' },
    neutral: { color: 'var(--text-secondary)', bg: 'var(--surface-2)' },
  }[tone]
  if (!items.length) return <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>—</span>
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
      {items.map(i => (
        <span key={i} style={{ fontSize: '10px', fontFamily: 'monospace', padding: '1px 6px', borderRadius: '6px',
          color: map.color, background: map.bg }}>{i}</span>
      ))}
    </div>
  )
}

// ── Audit-trail integrity tile (ADR-0031 D5) ────────────────────────────────
// Live tamper-evidence read from audit-service signed anchors. The per-event
// hash chain proves internal consistency; a signed anchor additionally catches a
// wholesale rewrite (the attested head no longer matches the live chain). Degrades
// calmly when the service is unreachable / the operator lacks an auditor role.
function AuditIntegrityTile({ integrity, available }: { integrity: AuditIntegrity | null; available: boolean }) {
  const { language } = useLanguage()
  const tt = (cs: string, en: string) => (language === 'cs' ? cs : en)
  const ok = available && integrity != null
  const broken = ok && integrity.status === 'BROKEN'
  const noAnchors = ok && integrity.anchorCount === 0
  const color = !ok ? 'var(--text-tertiary)' : broken ? '#dc2626' : noAnchors ? '#d97706' : '#16a34a'
  const bg = !ok ? 'var(--surface-2)' : broken ? '#fee2e2' : noAnchors ? '#fef9c3' : '#dcfce7'
  return (
    <div style={{ marginBottom: '14px', padding: '12px 14px', borderRadius: '10px', border: `1px solid ${color}40`, background: bg }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
        <Lock size={14} style={{ color }} />
        <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)' }}>
          {tt('Integrita auditní stopy', 'Audit-trail integrity')}
        </span>
        <span style={{ fontSize: '9px', fontWeight: 700, padding: '2px 7px', borderRadius: '8px', background: '#ede9fe', color: '#6366f1', letterSpacing: '0.04em' }}>ADR-0031 D5</span>
        <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '11px', fontWeight: 800, color, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
          {!ok ? tt('Nedostupné', 'Unavailable')
            : broken ? <><AlertOctagon size={13} /> BROKEN</>
            : noAnchors ? tt('Čeká na kotvu', 'Awaiting anchor')
            : <><CheckCircle2 size={13} /> INTACT</>}
        </span>
      </div>
      <p style={{ fontSize: '11px', color: 'var(--text-secondary)', margin: '8px 0 0', lineHeight: 1.5 }}>
        {!ok
          ? tt('Podepsané kotvy ověří, že hash-řetěz nebyl přepsán jako celek. audit-service je nedostupný (nenasazený / chybí role auditora).',
               'Signed anchors verify the hash chain was not rewritten wholesale. audit-service is unreachable (not deployed / missing auditor role).')
          : noAnchors
            ? tt('Řetěz je prázdný nebo plánovač zatím nepodepsal první kotvu.',
                 'The chain is empty or the scheduler has not signed the first anchor yet.')
            : tt(`${integrity.verifiedCount} z ${integrity.anchorCount} kotev ověřeno · ${integrity.unsignedCount} nepodepsaných · každá kotva potvrzuje, že atestovaná hlava sedí na živý řetěz.`,
                 `${integrity.verifiedCount} of ${integrity.anchorCount} anchors verified · ${integrity.unsignedCount} unsigned · each anchor confirms its attested head still matches the live chain.`)}
      </p>
      {broken && integrity.firstBroken && (
        <p style={{ fontSize: '10px', color: '#dc2626', margin: '6px 0 0', fontFamily: 'monospace' }}>
          {tt('První rozpor', 'First break')}:{' '}
          {[
            integrity.firstBroken.signatureInvalid ? tt('neplatný podpis', 'invalid signature') : null,
            integrity.firstBroken.headHashMismatch ? tt('přepsaná hlava řetězu', 'rewritten chain head') : null,
          ].filter(Boolean).join(' · ')}
          {integrity.firstBroken.lastEntryId ? ` (entry ${integrity.firstBroken.lastEntryId.slice(0, 8)}…)` : ''}
        </p>
      )}
    </div>
  )
}

// ── Main ────────────────────────────────────────────────────────────────────
function IAOpsContent() {
  const { t, language } = useLanguage()
  const [data, setData] = useState<GovData | null>(null)
  const [agentCosts, setAgentCosts] = useState<AgentCostEntry[]>([])
  const [costAnomalies, setCostAnomalies] = useState<FinOpsAnomaly[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const [rcaAsk, setRcaAsk] = useState('')
  const [rcaResult, setRcaResult] = useState<string | null>(null)
  const [rcaError, setRcaError] = useState<string | null>(null)
  const [rcaLoading, setRcaLoading] = useState(false)
  const [integrity, setIntegrity] = useState<AuditIntegrity | null>(null)
  const [integrityAvailable, setIntegrityAvailable] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const [govRes, aiCostRes, anomalyRes, integrityRes] = await Promise.all([
        fetch('/api/iaops/governance',    { cache: 'no-store' }),
        fetch('/api/finops/ai-costs',     { cache: 'no-store' }),
        fetch('/api/finops/anomalies',    { cache: 'no-store' }),
        // D5: live tamper-evidence via the generic service BFF (relays the operator's bearer).
        // .catch keeps a secondary-data failure from blanking the whole page.
        fetch('/api/svc/audit-service/api/v1/audit/anchors/verify', { cache: 'no-store' }).catch(() => null),
      ])
      if (!govRes.ok) { setUnavailable({ kind: 'error' }); return }
      setData(await govRes.json())
      if (aiCostRes.ok) {
        const ac = await aiCostRes.json() as { available: boolean; agents?: AgentCostEntry[] }
        setAgentCosts(ac.agents ?? [])
      }
      if (anomalyRes.ok) {
        const an = await anomalyRes.json() as { anomalies?: FinOpsAnomaly[] }
        setCostAnomalies(an.anomalies ?? [])
      }
      if (integrityRes && integrityRes.ok) {
        setIntegrity(await integrityRes.json() as AuditIntegrity)
        setIntegrityAvailable(true)
      } else {
        setIntegrity(null)
        setIntegrityAvailable(false)
      }
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
      setLastRefresh(new Date())
    }
  }, [])

  useEffect(() => { load() }, [load])

  const submitRca = useCallback(async () => {
    if (!rcaAsk.trim()) return
    setRcaLoading(true)
    setRcaResult(null)
    setRcaError(null)
    try {
      const res = await fetch('/api/iaops/rca', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ask: rcaAsk }),
        signal: AbortSignal.timeout(310_000),
      })
      const json = await res.json()
      if (!res.ok) { setRcaError(json.error ?? t('Vyšetřování selhalo', 'Investigation failed')); return }
      setRcaResult(json.rca ?? t('Žádná odpověď', 'No response'))
    } catch {
      setRcaError(t('HolmesGPT nedostupný', 'HolmesGPT unreachable'))
    } finally {
      setRcaLoading(false)
    }
  }, [rcaAsk, t])

  if (unavailable) {
    return <DataUnavailable kind={unavailable.kind} service="iaops" feature={t('AI governance', 'AI governance')} lang={language} />
  }

  const planeColor = (p: string) => p === 'control' ? '#6366f1' : '#0891b2'

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>

      {/* Header */}
      <div style={{ marginBottom: '24px', display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">IAOps</span>
          </div>
          <h1 style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', margin: '8px 0 4px', letterSpacing: '-0.03em' }}>
            {t('IAOps — governance AI', 'IAOps — AI Governance')}
          </h1>
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: 0 }}>
            {t(
              'Co AI děláme, proč, jak je to řízené a jak jsme compliant — ADR-0031',
              'What AI we run, why, how it is governed, and how we stay compliant — ADR-0031',
            )}
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          {lastRefresh && <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{lastRefresh.toLocaleTimeString()}</span>}
          <button onClick={load} disabled={loading}
            style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '7px 14px', borderRadius: '8px',
              border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)',
              fontSize: '12px', cursor: loading ? 'wait' : 'pointer' }}>
            <RefreshCw size={13} style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>
      </div>

      {loading && !data ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={16} style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '13px' }}>{t('Načítám AI governance…', 'Loading AI governance…')}</span>
        </div>
      ) : data ? (
        <>
          {/* ── A. Governance posture (hero) ── */}
          <div style={{ background: 'linear-gradient(135deg, rgba(99,102,241,0.06), rgba(8,145,178,0.04))',
            border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '22px 24px', marginBottom: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '14px' }}>
              <Bot size={18} style={{ color: '#6366f1' }} />
              <span style={{ fontSize: '15px', fontWeight: 800, color: 'var(--text-primary)' }}>
                {t('Stav governance', 'Governance posture')}
              </span>
              <span style={{ fontSize: '11px', fontWeight: 700, padding: '2px 8px', borderRadius: '10px',
                background: '#ede9fe', color: '#6366f1' }}>{data.adrRef} · {data.adrStatus}</span>
            </div>

            {/* Governing principle */}
            <p style={{ fontSize: '13px', color: 'var(--text-primary)', margin: '0 0 16px', fontWeight: 600, lineHeight: 1.5 }}>
              {t(
                '„Agenti navrhují, governance rozhoduje. Agent nikdy nemá víc oprávnění než člověk — má méně."',
                '“Agents propose; governance disposes. An agent never holds more privilege than a human — it holds less.”',
              )}
            </p>

            <div className="grid-4">
              {[
                { label: t('Fáze (ADR-0031)', 'Phase (ADR-0031)'), value: `${data.phase}/${data.totalPhases}`, sub: data.phaseLabel, color: '#6366f1' },
                { label: t('Vynucování', 'Enforcement'), value: data.enforcement === 'advisory' ? t('Advisory (audit)', 'Advisory (audit)') : data.enforcement, sub: t(`Default: ${data.policyDefault} (deny-by-default)`, `Default: ${data.policyDefault} (deny-by-default)`), color: '#d97706' },
                { label: t('Agentů jedná', 'Agents acting'), value: String(data.agentsActing), sub: t(`${data.agentCount} charterů definováno`, `${data.agentCount} charters defined`), color: '#16a34a' },
                { label: t('Roadmapa D1–D9', 'Roadmap D1–D9'), value: `${data.decisionSummary.built}/${data.decisionSummary.total}`, sub: t(`${data.decisionSummary.partial} částečně · ${data.decisionSummary.planned} plánováno`, `${data.decisionSummary.partial} partial · ${data.decisionSummary.planned} planned`), color: '#0891b2' },
              ].map(k => (
                <div key={k.label} className="stat-card">
                  <div style={{ fontSize: '22px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '2px' }}>{k.value}</div>
                  <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)' }}>{k.label}</div>
                  <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{k.sub}</div>
                </div>
              ))}
            </div>

            {/* What / Why / How */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '14px', marginTop: '16px' }}>
              {[
                { h: t('Co děláme', 'What we run'), b: t('Dvě populace agentů na jednom řízeném základu: control (dohled — AML/sankce/GDPR, jen návrhy) a development (jeden agent na doménu, otevírá PR, nemerguje).', 'Two agent populations on one governed substrate: control (oversight — AML/sanctions/GDPR, proposals only) and development (one agent per domain, opens PRs, never merges).') },
                { h: t('Proč', 'Why'), b: t('Regulace (EU AI Act, DORA, GDPR, PCI) vyžaduje human oversight, záznamy a logování. Tyto kontroly nejsou ergonomie, jsou to compliance.', 'Regulation (EU AI Act, DORA, GDPR, PCI) mandates human oversight, record-keeping and logging. These controls are not ergonomics — they are the compliance surface.') },
                { h: t('Jak', 'How'), b: t('Každá akce agenta projde stejnými branami jako člověk: charter (agents.yaml) → OPA policy → required approvals → AI-attributed audit. Deny-by-default.', 'Every agent action passes the same gates as a human: charter (agents.yaml) → OPA policy → required approvals → AI-attributed audit. Deny-by-default.') },
              ].map(x => (
                <div key={String(x.h)} style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '10px', padding: '12px 14px' }}>
                  <div style={{ fontSize: '12px', fontWeight: 700, color: '#6366f1', marginBottom: '4px' }}>{x.h}</div>
                  <div style={{ fontSize: '11px', color: 'var(--text-secondary)', lineHeight: 1.55 }}>{x.b}</div>
                </div>
              ))}
            </div>
          </div>

          {/* Tool tiers — what MCP/the bot can & cannot do */}
          {Object.keys(data.toolTiers).length > 0 && (
            <Card>
              <SectionTitle icon={<Lock size={16} />}
                sub={t('Tiery MCP nástrojů (agents.yaml). „deny" je hard-forbidden pro všechny agenty a nikdy se neregistruje.', 'MCP tool tiers (agents.yaml). The "deny" tier is hard-forbidden for all agents and never registered.')}>
                {t('Co MCP a bot smí — tiery nástrojů', 'What MCP & the bot can do — tool tiers')}
              </SectionTitle>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: '12px' }}>
                {([['read', 'neutral'], ['write_proposal', 'allow'], ['deny', 'deny']] as const).map(([tier, tone]) => (
                  data.toolTiers[tier] ? (
                    <div key={tier} style={{ padding: '12px 14px', borderRadius: '10px', border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                      <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '8px', fontFamily: 'monospace' }}>
                        {tier === 'read' ? t('read (jen čtení, PII masked)', 'read (read-only, PII masked)')
                          : tier === 'write_proposal' ? t('write_proposal (jen návrh)', 'write_proposal (proposal only)')
                          : t('deny (zakázáno všem)', 'deny (forbidden for all)')}
                      </div>
                      <Chips items={data.toolTiers[tier]} tone={tone} />
                    </div>
                  ) : null
                ))}
              </div>
            </Card>
          )}

          {/* ── B. Agent roster ── */}
          <Card>
            <SectionTitle icon={<Users size={16} />}
              sub={data.chartersAvailable
                ? t('Chartery z agents.yaml — jediná strojově čitelná pravda (konzumuje ji OPA gate i runtime).', 'Charters from agents.yaml — the single machine-readable source of truth (consumed by the OPA gate and the runtime).')
                : t('agents.yaml není v image — chartery nelze načíst.', 'agents.yaml is not bundled — charters unavailable.')}>
              {t('Agenti a jejich chartery', 'Agents & their charters')}
            </SectionTitle>

            {!data.chartersAvailable ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '14px', borderRadius: '8px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                <Info size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                  {t('agents.yaml nebyl nalezen v image. Po nasazení s bundlem se chartery zobrazí.', 'agents.yaml was not found in the image. Charters appear once deployed with the bundle.')}
                </span>
              </div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(380px, 1fr))', gap: '14px' }}>
                {data.agents.map(a => {
                  const costEntry = agentCosts.find(c => c.agentId === a.id)
                  const budgetPct = costEntry?.budgetUsedPct ?? null
                  const budgetColor = budgetPct == null ? 'var(--text-tertiary)'
                    : budgetPct > 100 ? '#dc2626'
                    : budgetPct > 80  ? '#d97706'
                    : '#16a34a'
                  const isExceeded = costEntry?.burnRate === 'exceeded'
                  const isFinopsAgent = a.id === 'finops-agent'
                  return (
                    <div key={a.id} style={{ padding: '16px', borderRadius: '12px', border: `1px solid ${isExceeded ? '#fca5a5' : 'var(--border)'}`, background: 'var(--surface-2)' }}>
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '6px' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                          <span style={{ fontSize: '14px', fontWeight: 800, color: 'var(--text-primary)', fontFamily: 'monospace' }}>{a.id}</span>
                          {isExceeded && (
                            <span style={{ display: 'inline-flex', alignItems: 'center', gap: '3px',
                              fontSize: '9px', fontWeight: 700, padding: '2px 6px', borderRadius: '8px',
                              background: '#fee2e2', color: '#dc2626' }}>
                              <span style={{ width: '5px', height: '5px', borderRadius: '50%', background: '#dc2626', display: 'inline-block' }} />
                              {t('Budget!', 'Budget!')}
                            </span>
                          )}
                        </div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '10px',
                            color: planeColor(a.plane), background: `${planeColor(a.plane)}18`, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                            {a.plane}
                          </span>
                          {isFinopsAgent && (
                            <button
                              onClick={() => alert(t('Funkce přijde v P4 (HITL backend)', 'Feature coming in P4 (HITL backend)'))}
                              style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '8px',
                                border: '1px solid #6366f1', background: 'transparent', color: '#6366f1', cursor: 'pointer' }}>
                              {t('Spustit analýzu', 'Trigger Analysis')}
                            </button>
                          )}
                        </div>
                      </div>
                      <p style={{ fontSize: '11px', color: 'var(--text-secondary)', margin: '0 0 12px', lineHeight: 1.5 }}>{a.charter}</p>

                      {/* Cost / Budget column */}
                      {costEntry && (
                        <div style={{ padding: '8px 10px', borderRadius: '8px', background: 'var(--surface)', border: '1px solid var(--border)', marginBottom: '10px' }}>
                          <div style={{ fontSize: '10px', fontWeight: 700, color: 'var(--text-tertiary)', marginBottom: '6px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                            {t('Náklady / Budget', 'Cost / Budget')}
                          </div>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }}>
                            <span style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                              24h: <strong style={{ fontFamily: 'monospace', color: 'var(--text-primary)' }}>${costEntry.costLast24hUsd.toFixed(2)}</strong>
                            </span>
                            <span style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                              7d: <strong style={{ fontFamily: 'monospace', color: 'var(--text-primary)' }}>${costEntry.costLast7dUsd.toFixed(2)}</strong>
                            </span>
                            {budgetPct != null && (
                              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', flex: 1, minWidth: '120px' }}>
                                <div style={{ flex: 1, height: '5px', background: 'var(--surface-3)', borderRadius: '3px', overflow: 'hidden' }}>
                                  <div style={{ width: `${Math.min(budgetPct, 100)}%`, height: '100%', background: budgetColor, borderRadius: '3px' }} />
                                </div>
                                <span style={{ fontSize: '11px', fontWeight: 700, color: budgetColor, minWidth: '36px' }}>
                                  {budgetPct.toFixed(0)}%
                                </span>
                              </div>
                            )}
                          </div>
                        </div>
                      )}

                      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', fontSize: '11px' }}>
                        <div>
                          <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Čte (PII', 'Reads (PII')} {a.pii})</div>
                          <Chips items={a.dataRead} tone="neutral" />
                        </div>
                        <div>
                          <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Smí nástroje', 'Allowed tools')}</div>
                          <Chips items={a.toolsAllow} tone="allow" />
                        </div>
                        <div>
                          <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Zakázáno', 'Denied')}</div>
                          <Chips items={a.toolsDeny} tone="deny" />
                        </div>
                        {a.owns.length > 0 && (
                          <div>
                            <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Vlastní služby', 'Owns services')}</div>
                            <Chips items={a.owns} tone="neutral" />
                          </div>
                        )}
                        <div>
                          <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Vyžaduje člověka', 'Requires human')}</div>
                          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                            {a.requiresHuman.map(r => (
                              <span key={r} style={{ fontSize: '10px', padding: '1px 6px', borderRadius: '6px', background: '#fef9c3', color: '#92400e' }}>{r}</span>
                            ))}
                          </div>
                        </div>
                        <div style={{ display: 'flex', gap: '12px', paddingTop: '6px', borderTop: '1px solid var(--border)', color: 'var(--text-tertiary)' }}>
                          <span>{t('Limit', 'Budget')}: <strong style={{ color: 'var(--text-secondary)' }}>{a.tokensPerRun ? `${(a.tokensPerRun / 1000).toFixed(0)}k tok/run` : '—'}</strong></span>
                          <span><strong style={{ color: 'var(--text-secondary)' }}>{a.runsPerDay ?? '—'}</strong> {t('běhů/den', 'runs/day')}</span>
                        </div>
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </Card>

          {/* ── C. Cost Anomalies (ADR-0112 D1–D5) — shared AgentInsightsPanel (admin-ui agent-output rule) ── */}
          <AgentInsightsPanel
            icon={<AlertOctagon size={16} />}
            title={t('Cost anomálie', 'Cost Anomalies')}
            subtitle={t(
              'Aktivní FinOps anomálie z Alertmanageru (D1–D5 detektory, ADR-0112). Agent navrhuje, schvaluje člověk — HITL backend přijde v P4 (tlačítka zatím logují do konzole).',
              'Active FinOps anomalies from Alertmanager (D1–D5 detectors, ADR-0112). The agent proposes; a human approves — HITL backend arrives in P4 (buttons currently log to console).',
            )}
            findings={costAnomalies.map(a => toAgentFinding(a, t))}
            emptyMessage={t(
              'Žádné aktivní cost anomálie — Alertmanager nedosažitelný nebo žádné finops-agent alerty.',
              'No active cost anomalies — Alertmanager unreachable or no finops-agent alerts firing.',
            )}
            onApprove={id => console.log('HITL approve', id)}
            onReject={id => console.log('HITL dismiss', id)}
            decideLabels={{ approve: t('Schválit', 'Approve'), reject: t('Odmítnout', 'Dismiss') }}
          />

          {/* ── D. AI audit trail ── */}
          <Card>
            <SectionTitle icon={<ScrollText size={16} />}
              sub={t('Jak je za AI auditní stopa (ADR-0031 D5). Každá akce agenta = AuditEvent (actorType=AI_AGENT).', 'How AI is audited (ADR-0031 D5). Every agent action = an AuditEvent (actorType=AI_AGENT).')}>
              {t('Auditní stopa AI', 'AI audit trail')}
            </SectionTitle>
            <AuditIntegrityTile integrity={integrity} available={integrityAvailable} />
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '14px' }}>
              <div>
                <div style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-secondary)', marginBottom: '6px' }}>{t('Co se zachytí', 'What is captured')}</div>
                <Chips items={data.auditTrail.capture} tone="neutral" />
                <div style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-secondary)', margin: '12px 0 6px' }}>{t('Pipeline', 'Pipeline')}</div>
                <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '4px' }}>
                  {data.auditTrail.pipeline.map((p, i) => (
                    <span key={p} style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                      <span style={{ fontSize: '10px', fontFamily: 'monospace', padding: '2px 7px', borderRadius: '6px', background: 'var(--surface-3)', color: 'var(--text-secondary)' }}>{p}</span>
                      {i < data.auditTrail.pipeline.length - 1 && <span style={{ color: 'var(--text-tertiary)', fontSize: '11px' }}>→</span>}
                    </span>
                  ))}
                </div>
              </div>
              <div>
                <div style={{ fontSize: '11px', fontWeight: 700, color: '#16a34a', marginBottom: '6px', display: 'flex', alignItems: 'center', gap: '5px' }}>
                  <CheckCircle2 size={13} /> {t('Živé', 'Live now')}
                </div>
                <ul style={{ margin: '0 0 12px', paddingLeft: '16px' }}>
                  {data.auditTrail.live.map(x => <li key={x} style={{ fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '3px' }}>{x}</li>)}
                </ul>
                <div style={{ fontSize: '11px', fontWeight: 700, color: '#6366f1', marginBottom: '6px', display: 'flex', alignItems: 'center', gap: '5px' }}>
                  <CircleDashed size={13} /> {t('Plánováno', 'Planned')}
                </div>
                <ul style={{ margin: 0, paddingLeft: '16px' }}>
                  {data.auditTrail.planned.map(x => <li key={x} style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '3px' }}>{x}</li>)}
                </ul>
              </div>
            </div>
            <div style={{ marginTop: '14px', display: 'flex', alignItems: 'flex-start', gap: '8px', padding: '10px 14px',
              borderRadius: '8px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
              <Info size={13} style={{ color: 'var(--text-tertiary)', marginTop: '1px', flexShrink: 0 }} />
              <span style={{ fontSize: '11px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                {t(
                  'Živý výpis AI eventů podle aktéra zde zatím není — audit-service nabízí jen dotaz podle aggregateId. By-actor endpoint je plánovaný; do té doby je auditní stopa dostupná přes audit-service store, ne přes tuto obrazovku.',
                  'A live by-actor list of AI events is not shown here yet — audit-service only exposes a by-aggregateId query. A by-actor endpoint is planned; until then the trail lives in the audit-service store, not on this screen.',
                )}
              </span>
            </div>
          </Card>

          {/* ── F. HolmesGPT RCA (D9 — read-only oversight agent) ── */}
          <Card>
            <SectionTitle icon={<Search size={16} />}
              sub={t(
                'Zadej popis alertu nebo problému a HolmesGPT (Davis-lite) ho vyšetří — stáhne metriky z Prometheu a stav clusteru, pak navrhne pravděpodobnou příčinu. Read-only, nic nespouští.',
                'Describe an alert or incident and HolmesGPT (Davis-lite) investigates — it pulls Prometheus metrics and cluster state, then proposes a probable root cause. Read-only, triggers nothing.',
              )}>
              {t('Holmes RCA — vyšetřování alertu', 'Holmes RCA — alert investigation')}
            </SectionTitle>
            <div style={{ display: 'flex', gap: '8px', marginBottom: '12px' }}>
              <textarea
                value={rcaAsk}
                onChange={e => setRcaAsk(e.target.value)}
                disabled={rcaLoading}
                placeholder={t(
                  'Napr.: alert PodCrashLooping, namespace payments, pod payments-service-xxx, restartoval 8× za 15 minut, OOMKilled…',
                  'E.g.: alert PodCrashLooping, namespace payments, pod payments-service-xxx, restarted 8× in 15 minutes, OOMKilled…',
                )}
                rows={3}
                style={{
                  flex: 1, resize: 'vertical', fontSize: '12px', fontFamily: 'monospace',
                  padding: '8px 10px', borderRadius: '8px', border: '1px solid var(--border)',
                  background: 'var(--surface-2)', color: 'var(--text-primary)',
                  outline: 'none', opacity: rcaLoading ? 0.6 : 1,
                }}
              />
              <button
                onClick={submitRca}
                disabled={rcaLoading || !rcaAsk.trim()}
                style={{
                  alignSelf: 'flex-end', padding: '8px 16px', borderRadius: '8px', border: 'none',
                  background: rcaLoading || !rcaAsk.trim() ? 'var(--surface-2)' : '#6366f1',
                  color: rcaLoading || !rcaAsk.trim() ? 'var(--text-tertiary)' : '#fff',
                  fontSize: '12px', fontWeight: 700, cursor: rcaLoading || !rcaAsk.trim() ? 'default' : 'pointer',
                  display: 'flex', alignItems: 'center', gap: '6px', whiteSpace: 'nowrap',
                }}
              >
                {rcaLoading
                  ? <><Loader2 size={13} style={{ animation: 'spin 1s linear infinite' }} />{t('Vyšetřuji…', 'Investigating…')}</>
                  : <><Search size={13} />{t('Vyšetřit', 'Investigate')}</>}
              </button>
            </div>
            {rcaError && (
              <div style={{ fontSize: '12px', color: '#dc2626', padding: '8px 12px',
                background: '#fee2e2', borderRadius: '8px', border: '1px solid #fca5a5' }}>
                {rcaError}
              </div>
            )}
            {rcaResult && (
              <div style={{ fontSize: '12px', lineHeight: 1.7, padding: '12px 14px',
                background: 'var(--surface-2)', borderRadius: '8px', border: '1px solid var(--border)',
                whiteSpace: 'pre-wrap', fontFamily: 'monospace', color: 'var(--text-primary)' }}>
                {rcaResult}
              </div>
            )}
            {!rcaResult && !rcaError && !rcaLoading && (
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', padding: '10px 14px',
                borderRadius: '8px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                <Info size={13} style={{ color: 'var(--text-tertiary)', marginTop: '1px', flexShrink: 0 }} />
                <span style={{ fontSize: '11px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                  {t(
                    'Model: NVIDIA NIM meta/llama-3.1-8b-instruct. Vyšetřování typicky trvá 30–60 s — HolmesGPT nejdřív stáhne Prometheus metriky a stav clusteru, pak zavolá LLM. Výsledek se neukládá.',
                    'Model: NVIDIA NIM meta/llama-3.1-8b-instruct. Investigation typically takes 30–60 s — HolmesGPT first fetches Prometheus metrics and cluster state, then calls the LLM. Result is not persisted.',
                  )}
                </span>
              </div>
            )}
          </Card>

          {/* ── G. Roadmap D1–D9 ── */}
          <Card>
            <SectionTitle icon={<GitBranch size={16} />}
              sub={t('9 rozhodnutí ADR-0031 a jejich reálný stav (ověřeno proti kódu).', 'The 9 ADR-0031 decisions and their real status (verified against the code).')}>
              {t('Roadmapa — rozhodnutí D1–D9', 'Roadmap — decisions D1–D9')}
            </SectionTitle>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {data.decisions.map(dec => (
                <div key={dec.id} style={{ display: 'flex', gap: '12px', padding: '10px 0', borderBottom: '1px solid var(--border)' }}>
                  <span style={{ fontSize: '12px', fontWeight: 800, fontFamily: 'monospace', color: '#6366f1', minWidth: '28px' }}>{dec.id}</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '2px', flexWrap: 'wrap' }}>
                      <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{dec.title}</span>
                      <StatusPill status={dec.status} />
                    </div>
                    <p style={{ fontSize: '11px', color: 'var(--text-secondary)', margin: 0, lineHeight: 1.5 }}>{dec.detail}</p>
                  </div>
                </div>
              ))}
            </div>
          </Card>

          {/* Compliance mapping */}
          <Card>
            <SectionTitle icon={<Scale size={16} />}
              sub={t('Co jako banka musíme splňovat a jak to tato architektura adresuje (ADR-0031 compliance impact).', 'What we must meet as a bank and how this architecture addresses it (ADR-0031 compliance impact).')}>
              {t('Compliance — regulační mapování', 'Compliance — regulatory mapping')}
            </SectionTitle>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
                <thead>
                  <tr style={{ borderBottom: '2px solid var(--border)' }}>
                    {[t('Rámec', 'Framework'), t('Požadavek', 'Requirement'), t('Kontrola', 'Control'), t('Stav', 'Status')].map(h => (
                      <th key={h} style={{ padding: '8px 12px', textAlign: 'left', color: 'var(--text-tertiary)', fontWeight: 600, fontSize: '11px', whiteSpace: 'nowrap' }}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.compliance.map(c => (
                    <tr key={c.framework} style={{ borderBottom: '1px solid var(--border)' }}>
                      <td style={{ padding: '10px 12px', fontWeight: 700, color: 'var(--text-primary)', whiteSpace: 'nowrap', verticalAlign: 'top' }}>{c.framework}</td>
                      <td style={{ padding: '10px 12px', color: 'var(--text-secondary)', verticalAlign: 'top', minWidth: '220px' }}>{c.requirement}</td>
                      <td style={{ padding: '10px 12px', color: 'var(--text-secondary)', verticalAlign: 'top', minWidth: '240px' }}>{c.control}</td>
                      <td style={{ padding: '10px 12px', verticalAlign: 'top' }}><StatusPill status={c.status} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p style={{ fontSize: '10px', color: 'var(--text-tertiary)', margin: '12px 0 0', lineHeight: 1.5 }}>
              {t(
                'Pozn.: Fáze 1 je advisory + audit-only — kontroly jsou zavedené, ale enforcement (OPA block) ještě neběží. EU AI Act se klasifikuje per agent; oversight/dev agenti jsou proposal-only (pravděpodobně limited risk). Žádný agent se nedotýká scoringu úvěruschopnosti.',
                'Note: Phase 1 is advisory + audit-only — controls are in place but enforcement (OPA block) is not yet live. EU AI Act is classified per agent; oversight/dev agents are proposal-only (likely limited risk). No agent touches creditworthiness scoring.',
              )}
            </p>
          </Card>
        </>
      ) : null}
    </div>
  )
}

export default function IAOpsPage() {
  return (
    <AuthGuard permission="system:view">
      <IAOpsContent />
    </AuthGuard>
  )
}
