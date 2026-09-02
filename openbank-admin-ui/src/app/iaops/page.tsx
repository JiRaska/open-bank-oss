// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import Link from 'next/link'
import Image from 'next/image'
import {
  Bot, RefreshCw, ScrollText, GitBranch, Scale,
  Info, CheckCircle2, CircleDashed, CircleDot, Lock, Users, Search, Loader2,
  AlertOctagon, ChevronRight, Sparkles, Hand, Fingerprint,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { AgentInsightsPanel } from '@/components/agent/AgentInsightsPanel'
import type { AgentFinding } from '@/components/agent/AgentInsightsPanel'
import { AgentPortrait, getAgentPersona } from '@/components/agent/AgentIdentity'
import { AgentMeshExplainer } from '@/components/agent/AgentMeshExplainer'
import { PageHeader } from '@/components/ui/PageHeader'
import { StatusBadge, type Tone } from '@/components/ui'
import styles from './IAOps.module.css'

// ── Types (mirror /api/iaops/governance) ───────────────────────────────────
type DStatus = 'built' | 'partial' | 'planned'
type PhaseStatus = 'complete' | 'active' | 'blocked' | 'planned'

interface Agent {
  id: string; plane: string; charter: string; owns: string[]; skills: string[]
  dataRead: string[]; pii: string; toolsAllow: string[]; toolsDeny: string[]
  requiresHuman: string[]; tokensPerRun: number | null; runsPerDay: number | null
}
interface Decision { id: string; title: string; status: DStatus; detail: string }
interface Compliance { framework: string; requirement: string; control: string; status: DStatus }
interface PhaseRoadmap { number: number; status: PhaseStatus; title: string; outcome: string }
interface ControlMaturity { current: number; total: number; label: string; achieved: string[]; remaining: string }
interface GovData {
  adrRef: string; adrStatus: string; phase: number; totalPhases: number; phaseLabel: string
  enforcement: string; policyDefault: string; agentsActing: number
  phaseRoadmap: PhaseRoadmap[]
  controlMaturity: ControlMaturity
  chartersAvailable: boolean; agentCount: number; agents: Agent[]
  toolTiers: Record<string, string[]>
  decisions: Decision[]; decisionSummary: { built: number; partial: number; planned: number; total: number }
  compliance: Compliance[]
  auditTrail: { capture: string[]; pipeline: string[]; live: string[]; planned: string[] }
}

interface AgentCostEntry {
  agentId: string
  costLast24hUsd: number
  costLast7dUsd: number
  budgetMonthlyUsd: number | null
  budgetUsedPct: number | null
  burnRate: 'low' | 'normal' | 'high' | 'exceeded'
}

interface MetricsCoverage {
  source: string
  retentionHours: number
  dataFrom: string
  dataTo: string
  lastSuccessfulLoad: string | null
  windows: Record<'24h' | '7d' | '30d', { requestedHours: number; availableHours: number; partial: boolean }>
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
const STATUS_CFG: Record<DStatus, { tone: Tone; en: string; cs: string }> = {
  built: { tone: 'success', en: 'Built', cs: 'Hotovo' },
  partial: { tone: 'warning', en: 'Partial', cs: 'Částečně' },
  planned: { tone: 'accent', en: 'Planned', cs: 'Plánováno' },
}

function StatusPill({ status }: { status: DStatus }) {
  const { language } = useLanguage()
  const c = STATUS_CFG[status]
  return <StatusBadge status={status} tone={c.tone} label={language === 'cs' ? c.cs : c.en} withDot />
}

function Card({ children, accent, id }: { children: React.ReactNode; accent?: string; id?: string }) {
  return (
    <div id={id} style={{ background: 'var(--surface)', border: accent ? `1px solid ${accent}40` : '1px solid var(--border)',
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

// ── Main ────────────────────────────────────────────────────────────────────
function IAOpsContent() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [data, setData] = useState<GovData | null>(null)
  const [agentCosts, setAgentCosts] = useState<AgentCostEntry[]>([])
  const [costCoverage, setCostCoverage] = useState<MetricsCoverage | null>(null)
  const [costAnomalies, setCostAnomalies] = useState<FinOpsAnomaly[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const [rcaAsk, setRcaAsk] = useState('')
  const [rcaResult, setRcaResult] = useState<string | null>(null)
  const [rcaError, setRcaError] = useState<string | null>(null)
  const [rcaLoading, setRcaLoading] = useState(false)
  const [crewFilter, setCrewFilter] = useState<'all' | 'control' | 'development' | 'customer'>('all')

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const [govRes, aiCostRes, anomalyRes] = await Promise.all([
        fetch('/api/iaops/governance',    { cache: 'no-store' }),
        fetch('/api/finops/ai-costs',     { cache: 'no-store' }),
        fetch('/api/finops/anomalies',    { cache: 'no-store' }),
      ])
      if (!govRes.ok) { setUnavailable({ kind: 'error' }); return }
      setData(await govRes.json())
      if (aiCostRes.ok) {
        const ac = await aiCostRes.json() as { available: boolean; agents?: AgentCostEntry[]; coverage?: MetricsCoverage }
        setAgentCosts(ac.agents ?? [])
        setCostCoverage(ac.coverage ?? null)
      }
      if (anomalyRes.ok) {
        const an = await anomalyRes.json() as { anomalies?: FinOpsAnomaly[] }
        setCostAnomalies(an.anomalies ?? [])
      }
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
      setLastRefresh(new Date())
    }
  }, [])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    if (!data || typeof window === 'undefined') return
    const fragment = window.location.hash.slice(1)
    if (fragment !== 'ai-swarm' && fragment !== 'ai-mesh' && fragment !== 'agent-roster') return

    const frame = window.requestAnimationFrame(() => {
      document.getElementById(fragment)?.scrollIntoView?.({ block: 'start' })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [data])

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
  const planeLabel = (p: string) => p === 'control'
    ? t('Dohled a provoz', 'Oversight & operations')
    : p === 'development'
      ? t('Vývoj', 'Engineering')
      : p === 'customer'
        ? t('Klientské služby', 'Customer services')
        : p
  const visibleAgents = data?.agents.filter(a => crewFilter === 'all' || a.plane === crewFilter) ?? []

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>

      <PageHeader
        icon={<Bot size={20} aria-hidden="true" />}
        title={t('Řídicí centrum agentů', 'Agent Control Room')}
        subtitle={t(
          'Co AI děláme, proč, jak je to řízené a jak jsme compliant — ADR-0031',
          'What AI we run, why, how it is governed, and how we stay compliant — ADR-0031',
        )}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">IAOps</span></div>}
        actions={<div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          {lastRefresh && <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{lastRefresh.toLocaleTimeString(dateLocale)}</span>}
          <button onClick={load} disabled={loading} type="button" aria-busy={loading}
            aria-label={t('Obnovit IAOPS', 'Refresh IAOPS')}
            style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '7px 14px', borderRadius: '8px',
              border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)',
              fontSize: '12px', cursor: loading ? 'wait' : 'pointer' }}>
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      {loading && !data ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={16} style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '13px' }}>{t('Načítám AI governance…', 'Loading AI governance…')}</span>
        </div>
      ) : data ? (
        <>
          {/* A human-first introduction. The generated illustration is an original OpenBank asset;
              the governed capabilities below still come from agents.yaml. */}
          <div className={styles.crewHero} style={{ borderRadius: '22px', marginBottom: '20px',
            background: 'linear-gradient(135deg, #111827 0%, #172554 55%, #0f766e 150%)', color: 'white',
            border: '1px solid rgba(148,163,184,0.25)', boxShadow: '0 18px 42px rgba(15,23,42,0.16)' }}>
            <div className={styles.crewCopy}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', alignSelf: 'flex-start',
                padding: '4px 9px', borderRadius: '20px', background: 'rgba(255,255,255,0.1)', border: '1px solid rgba(255,255,255,0.14)',
                fontSize: '10px', fontWeight: 800, letterSpacing: '0.08em', textTransform: 'uppercase', color: '#a5f3fc' }}>
                <Sparkles size={12} /> {t('AI posádka OpenBank', 'The OpenBank AI crew')}
              </span>
              <h2 style={{ fontSize: '27px', lineHeight: 1.12, letterSpacing: '-0.035em', margin: '14px 0 10px', maxWidth: '470px' }}>
                {t('Seznamte se s kolegy, kteří nikdy nerozhodují za vás.', 'Meet the colleagues who never decide for you.')}
              </h2>
              <p style={{ fontSize: '13px', lineHeight: 1.65, color: '#cbd5e1', margin: 0, maxWidth: '470px' }}>
                {t(
                  'Každý robot představuje jednoho skutečného agenta. Má jasnou práci, omezený přístup a okamžik, kdy musí předat rozhodnutí člověku.',
                  'Each robot represents a real agent. It has a clear job, limited access and a defined moment when a human must take over.',
                )}
              </p>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginTop: '18px' }}>
                {[
                  { icon: <Hand size={13} />, label: t('Agent připraví', 'Agent prepares') },
                  { icon: <Fingerprint size={13} />, label: t('Člověk rozhodne', 'Human decides') },
                  { icon: <ScrollText size={13} />, label: t('Audit vše zaznamená', 'Audit records everything') },
                ].map(item => (
                  <span key={item.label} style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '6px 9px',
                    borderRadius: '9px', background: 'rgba(255,255,255,0.08)', color: '#e2e8f0', fontSize: '10px', fontWeight: 700 }}>
                    {item.icon}{item.label}
                  </span>
                ))}
              </div>
            </div>
            <div className={styles.crewArt}>
              <Image src="/aiops-agent-crew.webp" alt={t('Originální tým pěti robotických AI agentů OpenBank', 'Original team of five OpenBank AI agent robots')}
                fill priority unoptimized sizes="(max-width: 900px) 100vw, 55vw"
                style={{ objectFit: 'cover', objectPosition: '52% 48%', opacity: 0.96 }} />
              <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(90deg, #172554 0%, transparent 32%)' }} />
              <span style={{ position: 'absolute', right: '12px', bottom: '9px', zIndex: 2, fontSize: '8px', color: 'rgba(226,232,240,.7)', letterSpacing: '0.04em' }}>
                {t('Vlastní vizuální koncept · bez postav třetích stran', 'Original visual concept · no third-party characters')}
              </span>
            </div>
          </div>

          <AgentMeshExplainer language={language} />

          {/* ── A. Governance posture (hero) ── */}
          <div style={{ background: 'linear-gradient(135deg, rgba(99,102,241,0.06), rgba(8,145,178,0.04))',
            border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '22px 24px', marginBottom: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '14px' }}>
              <Bot size={18} style={{ color: '#6366f1' }} />
              <span style={{ fontSize: '15px', fontWeight: 800, color: 'var(--text-primary)' }}>
                {t('Schválená governance roadmapa', 'Governance-approved roadmap')}
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
                { label: t('Fáze roadmapy (ADR-0031)', 'Roadmap phase (ADR-0031)'), value: `${data.phase}/${data.totalPhases}`, sub: t(`${data.phaseLabel} · není živá runtime atestace`, `${data.phaseLabel} · not live runtime evidence`), color: '#6366f1' },
                { label: t('Vynucování', 'Enforcement'), value: data.enforcement === 'advisory' ? t('Advisory (audit)', 'Advisory (audit)') : data.enforcement, sub: t(`Default: ${data.policyDefault} (deny-by-default)`, `Default: ${data.policyDefault} (deny-by-default)`), color: '#d97706' },
                { label: t('Autonomní změnoví agenti', 'Autonomous state-changing agents'), value: String(data.agentsActing), sub: t(`${data.agentCount} charterů definováno · není to živé počítadlo aktivity`, `${data.agentCount} charters defined · not a live activity count`), color: '#16a34a' },
                { label: t('Governance kontroly', 'Governance controls'), value: `${data.controlMaturity.current}/${data.controlMaturity.total}`, sub: t('Kontroly, ne oprávnění k autonomní změně', 'Controls, not authority for autonomous change'), color: '#0891b2' },
              ].map(k => (
                <div key={k.label} className="stat-card">
                  <div style={{ fontSize: '22px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '2px' }}>{k.value}</div>
                  <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)' }}>{k.label}</div>
                  <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{k.sub}</div>
                </div>
              ))}
            </div>

            <div style={{ marginTop: '14px', padding: '12px 14px', borderRadius: '10px', background: 'rgba(8,145,178,0.06)', border: '1px solid rgba(8,145,178,0.18)' }}>
              <div style={{ fontSize: '12px', fontWeight: 750, color: 'var(--text-primary)' }}>
                {t(
                  `${data.controlMaturity.current} z ${data.controlMaturity.total} ochranných pilířů je postavených.`,
                  `${data.controlMaturity.current} of ${data.controlMaturity.total} protective control families are built.`,
                )}
              </div>
              <p style={{ fontSize: '11px', color: 'var(--text-secondary)', lineHeight: 1.55, margin: '4px 0 0' }}>
                {t(
                  `${data.controlMaturity.label} To není povolení k autonomní změně — ta zůstává na ${data.phase}/${data.totalPhases}, dokud neexistuje nezávisle ověřený provozní důkaz.`,
                  `${data.controlMaturity.label} This is not permission for autonomous change — that remains at ${data.phase}/${data.totalPhases} until independently verified operational evidence exists.`,
                )}
              </p>
            </div>

            {/* What / Why / How */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '14px', marginTop: '16px' }}>
              {[
                { h: t('Co děláme', 'What we run'), b: t('Dohledové agenty a frontu pro lidské rozhodnutí. Vývojové PR workflow je další řízený krok — jeho stav ukazuje roadmapa níže.', 'Oversight agents and a queue for human decisions. Development PR workflow is the next governed step — its status is shown in the roadmap below.') },
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

          <Card>
            <SectionTitle icon={<GitBranch size={16} />}
              sub={t('Schválená governance roadmapa: změna fáze vyžaduje nezávisle ověřené provozní důkazy. Tato stránka je transparentní plán, ne živá runtime atestace.', 'Governance-approved roadmap: a phase change requires independently verified operational evidence. This page is a transparent plan, not a live runtime attestation.')}>
              {t('Jak bezpečně roste autonomie', 'How autonomy safely grows')}
            </SectionTitle>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '10px' }}>
              {(data.phaseRoadmap ?? []).map(item => {
                const tone: Record<PhaseStatus, { bg: string; border: string; text: string }> = {
                  complete: { bg: '#ecfdf5', border: '#a7f3d0', text: '#047857' },
                  active: { bg: '#eef2ff', border: '#c7d2fe', text: '#4338ca' },
                  blocked: { bg: '#fff7ed', border: '#fed7aa', text: '#c2410c' },
                  planned: { bg: '#f8fafc', border: '#cbd5e1', text: '#475569' },
                }
                const color = tone[item.status]
                const label: Record<PhaseStatus, string> = {
                  complete: t('uzavřeno', 'completed'), active: t('aktuální', 'current'),
                  blocked: t('kritéria nesplněna', 'criteria unmet'), planned: t('plánováno', 'planned'),
                }
                return <div key={item.number} style={{ padding: '13px', borderRadius: '10px', border: `1px solid ${color.border}`, background: color.bg }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', gap: '8px', alignItems: 'center', marginBottom: '6px' }}>
                    <strong style={{ fontSize: '12px', color: 'var(--text-primary)' }}>{t('Fáze', 'Phase')} {item.number}</strong>
                    <span style={{ color: color.text, fontSize: '10px', fontWeight: 800 }}>{label[item.status]}</span>
                  </div>
                  <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '5px' }}>{item.title}</div>
                  <p style={{ fontSize: '11px', color: 'var(--text-secondary)', lineHeight: 1.5, margin: 0 }}>{item.outcome}</p>
                </div>
              })}
            </div>
          </Card>

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
          <Card id="agent-roster">
            <SectionTitle icon={<Users size={16} />}
              sub={data.chartersAvailable
                ? t('Chartery z agents.yaml — jediná strojově čitelná pravda (konzumuje ji OPA gate i runtime).', 'Charters from agents.yaml — the single machine-readable source of truth (consumed by the OPA gate and the runtime).')
                : t('agents.yaml není v image — chartery nelze načíst.', 'agents.yaml is not bundled — charters unavailable.')}>
              {t('Agenti a jejich chartery', 'Agents & their charters')}
            </SectionTitle>

            {data.chartersAvailable && (
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap', margin: '-2px 0 16px' }}>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }} role="group" aria-label={t('Filtrovat AI posádku', 'Filter AI crew')}>
                  {([
                    ['all', t('Všichni', 'Everyone')],
                    ['control', t('Dohled a provoz', 'Oversight & operations')],
                    ['development', t('Vývoj', 'Engineering')],
                    ['customer', t('Klientské služby', 'Customer services')],
                  ] as const).map(([filter, label]) => (
                    <button key={filter} onClick={() => setCrewFilter(filter)} aria-pressed={crewFilter === filter}
                      style={{ padding: '6px 10px', borderRadius: '9px', border: crewFilter === filter ? '1px solid #818cf8' : '1px solid var(--border)',
                        background: crewFilter === filter ? '#eef2ff' : 'var(--surface)', color: crewFilter === filter ? '#4338ca' : 'var(--text-secondary)',
                        fontSize: '11px', fontWeight: 700, cursor: 'pointer' }}>
                      {label}
                    </button>
                  ))}
                </div>
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  {t(`${visibleAgents.length} z ${data.agents.length} kolegů`, `${visibleAgents.length} of ${data.agents.length} colleagues`)}
                </span>
              </div>
            )}

            {!data.chartersAvailable ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '14px', borderRadius: '8px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                <Info size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                  {t('agents.yaml nebyl nalezen v image. Po nasazení s bundlem se chartery zobrazí.', 'agents.yaml was not found in the image. Charters appear once deployed with the bundle.')}
                </span>
              </div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 400px), 1fr))', gap: '16px' }}>
                {visibleAgents.map(a => {
                  const persona = getAgentPersona(a.id, language)
                  const costEntry = agentCosts.find(c => c.agentId === a.id)
                  const budgetPct = costEntry?.budgetUsedPct ?? null
                  const budgetColor = budgetPct == null ? 'var(--text-tertiary)'
                    : budgetPct > 100 ? '#dc2626'
                    : budgetPct > 80  ? '#d97706'
                    : '#16a34a'
                  const isExceeded = costEntry?.burnRate === 'exceeded'
                  const isFinopsAgent = a.id === 'finops-agent'
                  return (
                    <article key={a.id}
                      style={{ position: 'relative', overflow: 'hidden', padding: '18px', borderRadius: '16px',
                        border: `1px solid ${isExceeded ? '#fca5a5' : `${persona.accent}30`}`,
                        background: `linear-gradient(145deg, var(--surface) 0%, ${persona.shell} 145%)`,
                        boxShadow: '0 6px 18px rgba(15,23,42,0.05)', transition: 'transform .15s ease, box-shadow .15s ease' }}>
                      <div style={{ display: 'flex', alignItems: 'flex-start', gap: '14px', marginBottom: '14px' }}>
                        <AgentPortrait agentId={a.id} />
                        <div style={{ minWidth: 0, flex: 1 }}>
                          <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '8px' }}>
                            <div>
                              <div style={{ display: 'flex', alignItems: 'center', gap: '7px', flexWrap: 'wrap' }}>
                                <Link href={`/iaops/agents/${encodeURIComponent(a.id)}`}
                                  aria-label={t(`Otevřít profil agenta ${persona.name}, ${persona.role}`, `Open ${persona.name}'s agent profile, ${persona.role}`)}
                                  style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', color: 'var(--text-primary)', textDecoration: 'none' }}>
                                  <span style={{ fontSize: '18px', fontWeight: 850, letterSpacing: '-0.025em' }}>{persona.name}</span>
                                  <ChevronRight size={16} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                                </Link>
                                {isExceeded && (
                                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: '3px', fontSize: '9px', fontWeight: 700,
                                    padding: '2px 6px', borderRadius: '8px', background: '#fee2e2', color: '#dc2626' }}>
                                    <span style={{ width: '5px', height: '5px', borderRadius: '50%', background: '#dc2626', display: 'inline-block' }} />
                                    {t('Budget!', 'Budget!')}
                                  </span>
                                )}
                              </div>
                              <div style={{ fontSize: '12px', fontWeight: 750, color: 'var(--text-primary)', marginTop: '1px' }}>{persona.role}</div>
                              <div style={{ fontSize: '9px', color: 'var(--text-tertiary)', fontFamily: 'monospace', marginTop: '4px' }}>{a.id}</div>
                            </div>
                          </div>
                          <p style={{ fontSize: '11px', color: 'var(--text-secondary)', margin: '9px 0 0', lineHeight: 1.55 }}>{persona.purpose}</p>
                        </div>
                      </div>

                      <div style={{ padding: '10px 12px', borderRadius: '10px', background: `${persona.accent}0d`, borderLeft: `3px solid ${persona.accent}`, marginBottom: '12px' }}>
                        <div style={{ fontSize: '9px', color: 'var(--text-secondary)', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '0.07em', marginBottom: '3px' }}>
                          {t('Proč je tu', 'Why this colleague matters')}
                        </div>
                        <div style={{ fontSize: '11px', color: 'var(--text-primary)', lineHeight: 1.5 }}>{persona.value}</div>
                      </div>

                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '9px', color: 'var(--text-tertiary)', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '0.07em', marginBottom: '6px' }}>
                          {t('Co umí', 'Skills in plain language')}
                        </div>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                          {persona.talents.map((talent, index) => (
                            <span key={talent} style={{ display: 'inline-flex', alignItems: 'center', gap: '5px', padding: '5px 8px', borderRadius: '8px',
                              background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text-secondary)', fontSize: '10px', fontWeight: 650 }}>
                              <span style={{ display: 'grid', placeItems: 'center', width: '15px', height: '15px', borderRadius: '5px',
                                background: `${persona.accent}${index === 0 ? '24' : '14'}`, color: persona.accent }}>
                                <Sparkles size={9} />
                              </span>
                              {talent}
                            </span>
                          ))}
                        </div>
                      </div>

                      <div style={{ display: 'flex', alignItems: 'center', gap: '7px', flexWrap: 'wrap', marginBottom: costEntry ? '10px' : '12px' }}>
                        <span style={{ fontSize: '9px', fontWeight: 800, padding: '3px 8px', borderRadius: '10px',
                          color: planeColor(a.plane), background: `${planeColor(a.plane)}16`, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                          {planeLabel(a.plane)}
                        </span>
                        <span style={{ fontSize: '9px', fontWeight: 750, padding: '3px 8px', borderRadius: '10px', background: '#fef9c3', color: '#92400e' }}>
                          <Hand size={9} style={{ verticalAlign: '-1px', marginRight: '3px' }} />
                          {t('Citlivé kroky schvaluje člověk', 'Human approval for sensitive steps')}
                        </span>
                        {isFinopsAgent && (
                          <span role="status" style={{ fontSize: '10px', fontWeight: 650, padding: '3px 8px', borderRadius: '8px', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)' }}>
                            {t('Analýza zatím není připojená k HITL backendu', 'Analysis is not connected to the HITL backend yet')}
                          </span>
                        )}
                      </div>

                      {/* Cost / Budget column: a missing budget is an explicit state, never an implied zero. */}
                      {costEntry && (
                        <div style={{ padding: '8px 10px', borderRadius: '8px', background: 'var(--surface)', border: '1px solid var(--border)', marginBottom: '10px' }}>
                          <div style={{ fontSize: '10px', fontWeight: 700, color: 'var(--text-tertiary)', marginBottom: '6px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                            {t('Náklady / rozpočet', 'Cost / Budget status')}
                          </div>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }}>
                            <span title={costCoverage ? `${costCoverage.dataFrom} → ${costCoverage.dataTo}` : undefined} style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                              {costCoverage?.windows['24h'].partial ? `${costCoverage.windows['24h'].availableHours}h / 24h` : '24h'}: <strong style={{ fontFamily: 'monospace', color: 'var(--text-primary)' }}>${costEntry.costLast24hUsd.toFixed(2)}</strong>
                            </span>
                            <span title={costCoverage ? `${costCoverage.dataFrom} → ${costCoverage.dataTo}` : undefined} style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                              {costCoverage?.windows['7d'].partial ? `${costCoverage.windows['7d'].availableHours}h / 7d` : '7d'}: <strong style={{ fontFamily: 'monospace', color: 'var(--text-primary)' }}>${costEntry.costLast7dUsd.toFixed(2)}</strong>
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
                            {budgetPct == null && (
                              <span role="status" style={{ fontSize: '10px', color: 'var(--text-tertiary)', lineHeight: 1.4 }}>
                                {t('Měsíční rozpočet není nastaven; intenzita používá denní prahy.', 'Monthly budget is not configured; burn rate uses daily thresholds ($1 / $5 / $10).')}
                              </span>
                            )}
                          </div>
                          {costCoverage && (
                            <div role="status" style={{ marginTop: '7px', fontSize: '9px', color: 'var(--text-tertiary)', lineHeight: 1.4 }}>
                              {costCoverage.source} · retention {costCoverage.retentionHours}h · {costCoverage.dataFrom} → {costCoverage.dataTo} · last successful load {costCoverage.lastSuccessfulLoad ?? 'unavailable'}
                            </div>
                          )}
                        </div>
                      )}

                      <details onClick={e => e.stopPropagation()} style={{ borderTop: '1px solid var(--border)', paddingTop: '10px' }}>
                        <summary style={{ cursor: 'pointer', color: 'var(--text-secondary)', fontSize: '10px', fontWeight: 750, userSelect: 'none' }}>
                          {t('Technický profil a mantinely', 'Technical profile & guardrails')}
                        </summary>
                        <div style={{ padding: '10px 2px 0', display: 'flex', flexDirection: 'column', gap: '8px', fontSize: '11px' }}>
                        <p style={{ fontSize: '11px', color: 'var(--text-secondary)', margin: '0 0 2px', lineHeight: 1.5 }}>{a.charter}</p>
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
                      </details>
                    </article>
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
              'Aktivní FinOps anomálie z Alertmanageru (D1–D5 detektory, ADR-0112). Tento přehled je pouze pro čtení: anomálie zatím nevytvářejí návrhy ve schvalovací frontě.',
              'Active FinOps anomalies from Alertmanager (D1–D5 detectors, ADR-0112). This view is read-only: anomalies do not yet create proposals in the approval queue.',
            )}
            findings={costAnomalies.map(a => toAgentFinding(a, t))}
            emptyMessage={t(
              'Žádné aktivní cost anomálie — Alertmanager nedosažitelný nebo žádné finops-agent alerty.',
              'No active cost anomalies — Alertmanager unreachable or no finops-agent alerts firing.',
            )}
          />

          {/* ── D. AI audit trail ── */}
          <Card>
            <SectionTitle icon={<ScrollText size={16} />}
              sub={t('Jak je za AI auditní stopa (ADR-0031 D5). Každá akce agenta = AuditEvent (actorType=AI_AGENT).', 'How AI is audited (ADR-0031 D5). Every agent action = an AuditEvent (actorType=AI_AGENT).')}>
              {t('Auditní stopa AI', 'AI audit trail')}
            </SectionTitle>
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
                aria-label={t('Popis alertu pro RCA', 'Alert description for RCA')}
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
                'Pozn.: Fáze 1 je vynucovaná, je-li PDP dostupný: policy gate je deny-by-default a OPA blokuje nepovolené volání nástrojů. Při výpadku PDP se režim degraduje na advisory. Fáze 2 zůstává read-only a proposal-only — každý návrh rozhoduje člověk. EU AI Act se klasifikuje per agent; žádný agent se nedotýká scoringu úvěruschopnosti.',
                'Note: Phase 1 is enforced while the PDP is available: the policy gate is deny-by-default and OPA blocks disallowed tool calls. A PDP outage degrades the gate to advisory. Phase 2 remains read-only and proposal-only — a human decides every proposal. EU AI Act is classified per agent; no agent touches creditworthiness scoring.',
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
