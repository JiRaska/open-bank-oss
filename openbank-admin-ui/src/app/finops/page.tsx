// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import {
  RefreshCw, ShieldCheck, AlertTriangle, Clock, DollarSign,
  Cpu, Server, Database, Zap, Info, Calendar, PieChart, TrendingDown, TrendingUp,
  Activity, Bot,
} from 'lucide-react'
import Link from 'next/link'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { AgentInsightsPanel } from '@/components/agent/AgentInsightsPanel'
import type { AgentFinding } from '@/components/agent/AgentInsightsPanel'
import { PageHeader } from '@/components/ui/PageHeader'
import { StatusBadge, type Tone } from '@/components/ui'

// ── Types ─────────────────────────────────────────────────────────────────────

interface EksVersion {
  version: string
  eksRelease: string
  standardSupportEnds: string
  extendedSupportEnds: string
  daysToStandardEnd: number
  daysToExtendedEnd: number
  tier: 'upcoming' | 'standard' | 'extended' | 'end_of_life'
  runwayStatus: 'ok' | 'warn' | 'critical'
  isCurrent: boolean
}

interface PlatformComponent {
  name: string
  kind: string
  version: string
  tier: string
  managedBy: string
  standardEnd: string | null
  daysRemaining: number | null
}

interface LifecycleData {
  currentVersion: string
  currentTier: string
  daysToStandardEnd: number
  runwayStatus: 'ok' | 'warn' | 'critical'
  standardSupportEnds: string
  extendedSupportEnds: string
  monthlyEstimate: number
  annualEstimate: number
  monthlySavingsVsExtended: number
  annualSavingsVsExtended: number
  minRunwayDays: number
  versions: EksVersion[]
  components: PlatformComponent[]
  dataSource: 'file' | 'embedded'
  lastRefreshed: string
  adrRef: string
}

interface ServiceResource {
  name: string
  short: string
  heap: { usedBytes: number; maxBytes: number; pct: number | null }
  cpuCoresUsed: number | null
  requestsPerSec: number | null
  efficiency: 'underutilised' | 'normal' | 'high' | 'unknown'
}

interface ResourceData {
  available: boolean
  fleetHeapPct: number | null
  underutilisedCount: number
  serviceCount: number
  services: ServiceResource[]
  collectedAt: string
}

interface ServiceCost { name: string; amount: number; domain: string }
interface DailyCost { date: string; amount: number }
interface ServiceEfficiency {
  namespace: string
  displayName: string
  cpu: { requestedMillicores: number | null; usedMillicores: number | null; efficiencyPct: number | null }
  memory: { requestedMiB: number | null; usedMiB: number | null; efficiencyPct: number | null }
  savingsPotential: 'high' | 'medium' | 'low' | 'unknown'
  vpaRecommendation: { cpuMillicores: number | null; memoryMiB: number | null }
}

interface RightSizingReport {
  available: boolean
  collectedAt: string
  fleetCpuEfficiencyPct: number | null
  fleetMemEfficiencyPct: number | null
  highSavingsCount: number
  services: ServiceEfficiency[]
  vpaHasData: boolean
}

interface CostReport {
  available: boolean
  reason?: string
  currency: string
  periodStart: string
  periodEnd: string
  total: number
  services: ServiceCost[]
  daily: DailyCost[]
  collectedAt: string | null
  source: string
}

interface AgentCostEntry {
  agentId: string
  model: string
  tokensLast24h: number
  tokensLast7d: number | null
  costLast24hUsd: number
  costLast7dUsd: number
  budgetMonthlyUsd: number | null
  budgetUsedPct: number | null
  burnRate: 'low' | 'normal' | 'high' | 'exceeded'
  anomalyZ: number | null
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

interface AiCostsData {
  available: boolean
  collectedAt: string
  totalCostLast7dUsd: number
  totalCostLast30dUsd: number | null
  selfHostedPct: number | null
  coverage: {
    source: 'prometheus'
    retentionHours: number
    dataFrom: string
    dataTo: string
    lastSuccessfulLoad: string | null
    windows: Record<'24h' | '7d' | '30d', { requestedHours: number; availableHours: number; partial: boolean }>
  }
  agents: AgentCostEntry[]
  anomalies: FinOpsAnomaly[]
}

// ── Helper components ─────────────────────────────────────────────────────────

function KpiCard({ icon, label, value, sub, color, accent }: {
  icon: React.ReactNode; label: string; value: string; sub: string; color: string; accent?: boolean
}) {
  return (
    <div className="stat-card" style={accent ? { border: `1px solid ${color}40`, background: `${color}06` } : {}}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '12px' }}>
        <div style={{ width: '36px', height: '36px', borderRadius: '10px',
          background: `${color}18`, display: 'flex', alignItems: 'center', justifyContent: 'center', color }}>
          {icon}
        </div>
      </div>
      <div style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '2px' }}>
        {value}
      </div>
      <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '2px' }}>{label}</div>
      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{sub}</div>
    </div>
  )
}

function RunwayBar({ days, max }: { days: number; max: number }) {
  const pct = Math.min(Math.round((days / max) * 100), 100)
  const color = days > 180 ? 'var(--success-text)' : days > 90 ? 'var(--warning-text)' : 'var(--danger-text)'
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flex: 1 }}>
      <div style={{ flex: 1, height: '6px', background: 'var(--surface-3)', borderRadius: '3px', overflow: 'hidden', minWidth: '60px' }}>
        <div style={{ width: `${pct}%`, height: '100%', background: color, borderRadius: '3px', transition: 'width 0.4s ease' }} />
      </div>
      <span style={{ fontSize: '11px', fontWeight: 700, color, minWidth: '42px', textAlign: 'right' }}>
        {days > 0 ? `${days}d` : 'EOL'}
      </span>
    </div>
  )
}

function TierBadge({ tier }: { tier: string }) {
  const cfg: Record<string, { label: string; tone: Tone }> = {
    standard: { label: 'Standard', tone: 'success' },
    supported: { label: 'Supported', tone: 'success' },
    lts: { label: 'LTS', tone: 'success' },
    rolling: { label: 'Rolling', tone: 'info' },
    extended: { label: 'Extended', tone: 'warning' },
    end_of_life: { label: 'EOL', tone: 'danger' },
    upcoming: { label: 'Upcoming', tone: 'accent' },
  }
  const c = cfg[tier] ?? { label: tier, tone: 'neutral' as Tone }
  return <StatusBadge status={tier} label={c.label} tone={c.tone} />
}

function KindIcon({ kind }: { kind: string }) {
  const icons: Record<string, React.ReactNode> = {
    kubernetes: <Server size={14} />,
    database: <Database size={14} />,
    messaging: <Zap size={14} />,
    cache: <Cpu size={14} />,
    runtime: <Cpu size={14} />,
  }
  return <span style={{ color: 'var(--text-tertiary)' }}>{icons[kind] ?? <Server size={14} />}</span>
}

function HeapBar({ pct, efficiency }: { pct: number | null; efficiency: string }) {
  if (pct === null) return <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>—</span>
  const color = efficiency === 'high'
    ? 'var(--danger-text)'
    : efficiency === 'normal'
      ? 'var(--success-text)'
      : 'var(--accent-text)'
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
      <div style={{ flex: 1, height: '5px', background: 'var(--surface-3)', borderRadius: '3px', overflow: 'hidden', minWidth: '60px' }}>
        <div style={{ width: `${pct}%`, height: '100%', background: color, borderRadius: '3px' }} />
      </div>
      <span style={{ fontSize: '11px', fontWeight: 700, color, minWidth: '34px', textAlign: 'right' }}>{pct}%</span>
    </div>
  )
}

// Per-day cloud spend trend — a hand-rolled inline SVG area chart (matches the
// panel's other hand-rolled bars; no chart lib). This is the series the team
// otherwise reads only in the AWS console. Higher spend is tinted as the
// "concern" direction (red) per the FinOps framing.
function DailySpendTrend({ daily, currency }: { daily: DailyCost[]; currency: string }) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const fmt = (v: number) => v.toLocaleString(locale, { maximumFractionDigits: 0 })

  if (!daily || daily.length < 2) {
    return (
      <div style={{ marginBottom: '20px', padding: '14px', borderRadius: '8px', background: 'var(--surface-2)', border: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: '8px' }}>
        <Activity size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
        <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
          {t('Denní trend výdajů se objeví po příštím sběru nákladů (DAILY granularita).', 'The daily spend trend will appear after the next cost collection (DAILY granularity).')}
        </span>
      </div>
    )
  }

  const W = 760, H = 150, padL = 6, padR = 6, padT = 14, padB = 22
  const amounts = daily.map(d => d.amount)
  const max = Math.max(...amounts)
  const min = Math.min(...amounts, 0)
  const n = daily.length
  const span = max - min || 1
  const xAt = (i: number) => padL + (i / (n - 1)) * (W - padL - padR)
  const yAt = (v: number) => padT + (1 - (v - min) / span) * (H - padT - padB)
  const pts = daily.map((d, i) => `${xAt(i).toFixed(1)},${yAt(d.amount).toFixed(1)}`)
  const line = `M ${pts.join(' L ')}`
  const area = `M ${xAt(0).toFixed(1)},${(H - padB).toFixed(1)} L ${pts.join(' L ')} L ${xAt(n - 1).toFixed(1)},${(H - padB).toFixed(1)} Z`
  const avg = amounts.reduce((s, a) => s + a, 0) / n
  const avgY = yAt(avg)
  const last = daily[n - 1]
  const prev = daily[n - 2]
  const dod = prev.amount ? ((last.amount - prev.amount) / prev.amount) * 100 : 0
  const up = last.amount >= prev.amount
  const fmtDate = (s: string) => { try { return new Date(s).toLocaleDateString(locale, { day: 'numeric', month: 'numeric' }) } catch { return s } }

  return (
    <div style={{ marginBottom: '20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px', flexWrap: 'wrap', gap: '8px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <Activity size={14} style={{ color: '#6366f1' }} />
          <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-secondary)' }}>
            {t('Denní trend výdajů', 'Daily spend trend')}
          </span>
          <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>({n} {t('dní', 'days')} · {currency})</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
            {t('poslední den', 'last day')}: <strong style={{ color: 'var(--text-primary)' }}>${fmt(last.amount)}</strong>
          </span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '3px', fontSize: '11px', fontWeight: 700,
            color: up ? 'var(--danger-text)' : 'var(--success-text)' }}
            title={t('Změna oproti předchozímu dni', 'Change vs. the previous day')}>
            {up ? <TrendingUp size={12} /> : <TrendingDown size={12} />}
            {up ? '+' : ''}{dod.toFixed(0)}%
          </span>
        </div>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" style={{ width: '100%', height: '150px', display: 'block' }}
        role="img" aria-label={t('Graf denních cloudových výdajů', 'Daily cloud spend chart')}>
        <defs>
          <linearGradient id="spendGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#6366f1" stopOpacity="0.28" />
            <stop offset="100%" stopColor="#6366f1" stopOpacity="0.02" />
          </linearGradient>
        </defs>
        <line x1={padL} y1={avgY} x2={W - padR} y2={avgY} stroke="var(--text-tertiary)" strokeWidth="1" strokeDasharray="4 4" opacity="0.5" />
        <path d={area} fill="url(#spendGrad)" />
        <path d={line} fill="none" stroke="#6366f1" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
        <circle cx={xAt(n - 1)} cy={yAt(last.amount)} r="3.5" fill="#6366f1" />
      </svg>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', color: 'var(--text-tertiary)', marginTop: '2px' }}>
        <span>{fmtDate(daily[0].date)}</span>
        <span>{t(`prům. $${fmt(avg)}/den · max $${fmt(max)}`, `avg $${fmt(avg)}/day · max $${fmt(max)}`)}</span>
        <span>{fmtDate(last.date)}</span>
      </div>
    </div>
  )
}

// Map a finops-agent anomaly → the shared AgentFinding view-model. The estimated
// monthly saving becomes a success-toned tag so the rendering matches every other
// agent surface (admin-ui agent-output rule).
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

// ── Main page ─────────────────────────────────────────────────────────────────

function FinOpsContent() {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [lifecycle, setLifecycle] = useState<LifecycleData | null>(null)
  const [resources, setResources] = useState<ResourceData | null>(null)
  const [costs, setCosts] = useState<CostReport | null>(null)
  const [rightSizing, setRightSizing] = useState<RightSizingReport | null>(null)
  const [aiCosts, setAiCosts] = useState<AiCostsData | null>(null)
  const [anomalies, setAnomalies] = useState<FinOpsAnomaly[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const [lcRes, rsRes, costRes, rsizRes, aiCostRes, anomalyRes] = await Promise.all([
        fetch('/api/finops/lifecycle',     { cache: 'no-store' }),
        fetch('/api/finops/resources',     { cache: 'no-store' }),
        fetch('/api/finops/costs',         { cache: 'no-store' }),
        fetch('/api/finops/right-sizing',  { cache: 'no-store' }),
        fetch('/api/finops/ai-costs',      { cache: 'no-store' }),
        fetch('/api/finops/anomalies',     { cache: 'no-store' }),
      ])

      if (!lcRes.ok) {
        setUnavailable({ kind: 'error' })
        return
      }

      setLifecycle(await lcRes.json())
      if (rsRes.ok)      setResources(await rsRes.json())
      if (costRes.ok)    setCosts(await costRes.json())
      if (rsizRes.ok)    setRightSizing(await rsizRes.json())
      if (aiCostRes.ok)  setAiCosts(await aiCostRes.json())
      if (anomalyRes.ok) {
        const aj = await anomalyRes.json() as { anomalies?: FinOpsAnomaly[] }
        setAnomalies(aj.anomalies ?? [])
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
    const id = setInterval(load, 60_000)
    return () => clearInterval(id)
  }, [load])

  if (unavailable) {
    return <DataUnavailable kind={unavailable.kind} service="finops" feature={t('FinOps přehled', 'FinOps overview')} lang={language} />
  }

  const tierColor = lifecycle?.currentTier === 'standard' ? '#16a34a'
    : lifecycle?.currentTier === 'extended' ? '#d97706'
    : '#dc2626'

  const runwayColor = lifecycle?.runwayStatus === 'ok' ? '#16a34a'
    : lifecycle?.runwayStatus === 'warn' ? '#d97706'
    : '#dc2626'

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>

      <PageHeader
        icon={<Bot size={20} aria-hidden="true" />}
        title={t('FinOps přehled', 'FinOps Overview')}
        subtitle={t(
          'Správa verzí, licencí a efektivity zdrojů — ADR-0054',
          'Version lifecycle, cost posture, and resource efficiency — ADR-0054',
        )}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">FinOps</span></div>}
        actions={<div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          {lastRefresh && (
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
              {t('Aktualizováno', 'Updated')} {lastRefresh.toLocaleTimeString(locale)}
            </span>
          )}
          <Link
            href="/finops/allocation"
            style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '7px 14px',
              borderRadius: '8px', border: '1px solid var(--border)', background: 'var(--surface)',
              color: 'var(--text-secondary)', fontSize: '12px', textDecoration: 'none' }}
          >
            <PieChart size={13} aria-hidden="true" />
            {t('Rozpad nákladů', 'Cost allocation')}
          </Link>
          <button
            type="button"
            onClick={load}
            disabled={loading}
            aria-busy={loading}
            aria-label={t('Obnovit FinOps náklady', 'Refresh FinOps costs')}
            style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '7px 14px',
              borderRadius: '8px', border: '1px solid var(--border)', background: 'var(--surface)',
              color: 'var(--text-secondary)', fontSize: '12px', cursor: loading ? 'wait' : 'pointer' }}
          >
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      {loading && !lifecycle ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={16} style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '13px' }}>{t('Načítám FinOps data…', 'Loading FinOps data…')}</span>
        </div>
      ) : lifecycle ? (
        <>
          {/* KPI row */}
          <div className="grid-4" style={{ marginBottom: '28px' }}>
            <KpiCard
              icon={<ShieldCheck size={18} />}
              label={t('Typ podpory EKS', 'EKS Support Tier')}
              value={lifecycle.currentTier === 'standard' ? 'Standard' : lifecycle.currentTier === 'extended' ? 'Extended' : 'EOL'}
              sub={`v${lifecycle.currentVersion} · $${lifecycle.monthlyEstimate.toLocaleString(locale)}/mo`}
              color={tierColor}
              accent
            />
            <KpiCard
              icon={<Clock size={18} />}
              label={t('Zbývá dní (standard)', 'Standard Support Runway')}
              value={lifecycle.daysToStandardEnd > 0 ? `${lifecycle.daysToStandardEnd}d` : 'EOL'}
              sub={t(`Konec: ${lifecycle.standardSupportEnds}`, `Ends: ${lifecycle.standardSupportEnds}`)}
              color={runwayColor}
              accent={lifecycle.runwayStatus !== 'ok'}
            />
            <KpiCard
              icon={<DollarSign size={18} />}
              label={t('Měsíční cloud náklady', 'Monthly Cloud Spend')}
              value={costs?.available ? `$${Math.round(costs.total).toLocaleString(locale)}` : '—'}
              sub={costs?.available
                ? t(`${costs.services.length} služeb · ${costs.periodStart}→${costs.periodEnd}`, `${costs.services.length} services · ${costs.periodStart}→${costs.periodEnd}`)
                : t('Cost Explorer snapshot není k dispozici', 'No Cost Explorer snapshot')}
              color='#16a34a'
              accent
            />
            <KpiCard
              icon={<TrendingDown size={18} />}
              label={t('Right-sizing: potenciál úspory', 'Right-sizing Savings Potential')}
              value={rightSizing?.available ? `${rightSizing.highSavingsCount}` : '—'}
              sub={rightSizing?.available
                ? t(`služeb silně naddimenzovaných (CPU<30% req)`, `services heavily over-provisioned (CPU<30% req)`)
                : t('Prometheus nedostupný', 'Prometheus unavailable')}
              color='#d97706'
              accent={Boolean(rightSizing?.available && rightSizing.highSavingsCount > 0)}
            />
          </div>

          {/* ── FinOps agent findings (AI) — directly below the KPIs (admin-ui agent-output rule) ── */}
          <AgentInsightsPanel
            title={t('FinOps náhledy (AI)', 'FinOps Insights (AI)')}
            subtitle={t(
              'Aktivní cost anomálie z finops-agenta (D1–D5 detektory z Alertmanageru — ADR-0112). Tento přehled je pouze pro čtení: alerty zatím nevytvářejí návrhy ve schvalovací frontě.',
              'Active cost anomalies from the finops-agent (D1–D5 detectors via Alertmanager — ADR-0112). This view is read-only: alerts do not yet create proposals in the approval queue.',
            )}
            findings={anomalies.map(a => toAgentFinding(a, t))}
            emptyMessage={t(
              'Žádné aktivní cost anomálie — Alertmanager nedosažitelný nebo žádné finops-agent alerty.',
              'No active cost anomalies — Alertmanager unreachable or no finops-agent alerts firing.',
            )}
            sourceLabel={t('Zdroj: Alertmanager (finops-agent)', 'Source: Alertmanager (finops-agent)')}
          />

          {/* Cloud Cost (AWS Cost Explorer snapshot) */}
          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '4px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <DollarSign size={16} style={{ color: '#16a34a' }} />
                <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('Celkové měsíční náklady — AWS Cost Explorer', 'Total Monthly Cloud Spend — AWS Cost Explorer')}
                </span>
              </div>
              {costs?.available && (() => {
                // Honest snapshot age. The cost number is a point-in-time snapshot
                // (baked at deploy, refreshed daily by the in-cluster CronJob), NOT a
                // live figure — so surface how old it actually is. Use lastRefresh
                // (a state Date) as "now" to keep render pure.
                const collected = costs.collectedAt ? new Date(costs.collectedAt) : null
                const ageDays = collected && lastRefresh
                  ? Math.max(0, Math.floor((lastRefresh.getTime() - collected.getTime()) / 86_400_000))
                  : null
                const stale = ageDays != null && ageDays >= 2
                return (
                  <span title={t('Snapshot z AWS Cost Exploreru — obnovuje se při deployi a denním CronJobem.', 'AWS Cost Explorer snapshot — refreshed on deploy and by the daily CronJob.')}
                    style={{ fontSize: '11px', fontWeight: stale ? 700 : 400,
                      color: stale ? 'var(--warning-text)' : 'var(--text-tertiary)',
                      background: stale ? 'var(--warning-bg)' : 'transparent',
                      border: stale ? '1px solid var(--warning-border)' : 'none',
                      borderRadius: '10px', padding: stale ? '2px 8px' : 0 }}>
                    {t('Snapshot', 'Snapshot')}: {collected ? collected.toLocaleDateString(language === 'cs' ? 'cs-CZ' : 'en-GB') : '—'}
                    {ageDays != null && ` · ${ageDays === 0 ? t('dnes', 'today') : t(`starý ${ageDays} d`, `${ageDays}d old`)}`}
                  </span>
                )
              })()}
            </div>
            <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '0 0 16px' }}>
              {t(
                'UnblendedCost z AWS Cost Explorer — co reálně zaplatíš. Snapshot z buildu (ADR-0054 fáze 2), admin-ui read-only.',
                'UnblendedCost from AWS Cost Explorer — what you actually pay. Build-time snapshot (ADR-0054 phase 2), admin-ui read-only.',
              )}
            </p>

            {!costs?.available ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '14px', borderRadius: '8px',
                background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                <Info size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                  {t('Cost snapshot není k dispozici.', 'Cost snapshot unavailable.')}
                </span>
              </div>
            ) : (() => {
              const DOMAIN_COLOR: Record<string, string> = {
                Platform: '#6366f1', Governance: '#d97706', Security: '#dc2626',
                Observability: '#0891b2', FinOps: '#16a34a', Tax: '#94a3b8',
              }
              const domainTotals: Record<string, number> = {}
              for (const s of costs.services) {
                const d = (s as ServiceCost).domain ?? 'Platform'
                domainTotals[d] = (domainTotals[d] ?? 0) + s.amount
              }
              const domains = Object.entries(domainTotals)
                .map(([domain, amount]) => ({ domain, amount: Math.round(amount * 100) / 100 }))
                .sort((a, b) => b.amount - a.amount)
              const tax = costs.services.filter(s => (s as ServiceCost).domain === 'Tax').reduce((sum, s) => sum + s.amount, 0)
              const exTax = Math.round((costs.total - tax) * 100) / 100
              return (
                <>
                  {/* PROMINENT TOTAL */}
                  <div style={{ display: 'flex', alignItems: 'baseline', gap: '14px', marginBottom: '20px',
                    padding: '16px 20px', borderRadius: '12px', background: 'rgba(22,163,74,0.05)', border: '1px solid #86efac' }}>
                    <span style={{ fontSize: '48px', fontWeight: 900, color: '#16a34a', letterSpacing: '-0.05em', lineHeight: 1 }}>
                      ${costs.total.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                    </span>
                    <div>
                      <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                        {costs.currency} · {t('posledních 30 dní', 'trailing 30 days')}
                      </div>
                      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                        {t(`bez daně $${exTax.toFixed(2)} · daň $${tax.toFixed(2)}`,
                           `ex-tax $${exTax.toFixed(2)} · tax $${tax.toFixed(2)}`)}
                      </div>
                    </div>
                  </div>

                  {/* LAST 7 DAYS — the window operators watch day-to-day, with a
                      week-over-week delta. Higher spend is red (bad), lower green. */}
                  {costs.daily.length >= 1 && (() => {
                    const sum = (a: DailyCost[]) => a.reduce((s, d) => s + d.amount, 0)
                    const last7 = costs.daily.slice(-7)
                    const prev7 = costs.daily.slice(-14, -7)
                    const l7 = sum(last7)
                    const avg = last7.length ? l7 / last7.length : 0
                    const delta = prev7.length > 0 && sum(prev7) > 0 ? ((l7 - sum(prev7)) / sum(prev7)) * 100 : null
                    const up = (delta ?? 0) >= 0
                    return (
                      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '18px', marginBottom: '18px',
                        padding: '14px 18px', borderRadius: '10px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                        <div>
                          <div style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                            {t('Posledních 7 dní', 'Last 7 days')}
                          </div>
                          <div style={{ fontSize: '26px', fontWeight: 800, color: 'var(--text-primary)', lineHeight: 1.15 }}>
                            ${l7.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                          </div>
                        </div>
                        <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                          {t('⌀ / den', 'avg / day')}: <strong>${avg.toFixed(2)}</strong>
                        </div>
                        {delta !== null && (
                          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 700,
                            color: up ? '#dc2626' : '#16a34a' }}>
                            {up ? <TrendingUp size={13} /> : <TrendingDown size={13} />}
                            {up ? '+' : ''}{delta.toFixed(1)}% {t('vs. předchozích 7 dní', 'vs. prior 7 days')}
                          </span>
                        )}
                      </div>
                    )
                  })()}

                  {/* DAILY SPEND TREND — the per-day series we otherwise only read in the console */}
                  <DailySpendTrend daily={costs.daily} currency={costs.currency} />

                  {/* BY DOMAIN — process/business view */}
                  <div style={{ marginBottom: '20px' }}>
                    <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-secondary)', marginBottom: '10px' }}>
                      {t('Rozpad po doménách (procesní pohled)', 'By domain (process / business view)')}
                    </div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '7px' }}>
                      {domains.map(({ domain, amount }) => {
                        const pct = costs.total > 0 ? (amount / costs.total) * 100 : 0
                        const color = DOMAIN_COLOR[domain] ?? '#6366f1'
                        return (
                          <div key={domain} style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                            <span style={{ fontSize: '12px', fontWeight: 700, color, width: '110px', flexShrink: 0 }}>{domain}</span>
                            <div style={{ flex: 1, height: '10px', background: 'var(--surface-3)', borderRadius: '5px', overflow: 'hidden' }}>
                              <div style={{ width: `${Math.max(pct, 0.5)}%`, height: '100%', background: color, borderRadius: '5px' }} />
                            </div>
                            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', minWidth: '36px', textAlign: 'right' }}>{pct.toFixed(0)}%</span>
                            <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)', fontFamily: 'monospace', minWidth: '72px', textAlign: 'right' }}>
                              ${amount.toFixed(2)}
                            </span>
                          </div>
                        )
                      })}
                    </div>
                  </div>

                  {/* BY AWS SERVICE — detail, collapsible */}
                  <details>
                    <summary style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-secondary)', cursor: 'pointer', marginBottom: '8px', userSelect: 'none' }}>
                      {t('Detail po AWS službách', 'Detail by AWS service')} ({costs.services.length})
                    </summary>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', marginTop: '8px' }}>
                      {costs.services.map(svc => {
                        const pct = costs.total > 0 ? (svc.amount / costs.total) * 100 : 0
                        const color = DOMAIN_COLOR[(svc as ServiceCost).domain ?? 'Platform'] ?? '#6366f1'
                        return (
                          <div key={svc.name} style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <span style={{ width: '8px', height: '8px', borderRadius: '50%', background: color, flexShrink: 0 }} />
                            <span title={svc.name} style={{ fontSize: '11px', color: 'var(--text-secondary)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', minWidth: 0 }}>
                              {svc.name}
                            </span>
                            <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', minWidth: '36px', textAlign: 'right' }}>{pct.toFixed(1)}%</span>
                            <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)', fontFamily: 'monospace', minWidth: '68px', textAlign: 'right' }}>
                              ${svc.amount.toFixed(2)}
                            </span>
                          </div>
                        )
                      })}
                    </div>
                  </details>
                </>
              )
            })()}
          </div>

          {/* EKS Version Timeline */}
          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <Server size={16} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('Amazon EKS — přehled verzí', 'Amazon EKS Version Lifecycle')}
                </span>
              </div>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                {t('Zdroj', 'Source')}: {lifecycle.dataSource === 'file' ? lifecycle.lastRefreshed : t('Zabudovaná data', 'Embedded data')}
              </span>
            </div>

            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
                <thead>
                  <tr style={{ borderBottom: '2px solid var(--border)' }}>
                    {[
                      t('Verze', 'Version'),
                      t('Vydání', 'Released'),
                      t('Konec standard. podpory', 'Standard Support End'),
                      t('Konec extended podpory', 'Extended Support End'),
                      t('Zbývá (standard)', 'Runway (standard)'),
                      t('Stav', 'Status'),
                    ].map(h => (
                      <th key={h} style={{ padding: '8px 12px', textAlign: 'left', color: 'var(--text-tertiary)',
                        fontWeight: 600, fontSize: '11px', whiteSpace: 'nowrap' }}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {lifecycle.versions.map(v => (
                    <tr key={v.version}
                      style={{
                        borderBottom: '1px solid var(--border)',
                        background: v.isCurrent ? 'rgba(99,102,241,0.04)' : 'transparent',
                      }}>
                      <td style={{ padding: '10px 12px', fontFamily: 'monospace', fontWeight: v.isCurrent ? 800 : 500, color: v.isCurrent ? '#6366f1' : 'var(--text-primary)', whiteSpace: 'nowrap' }}>
                        {v.isCurrent && <span style={{ marginRight: '6px', fontSize: '10px', background: '#6366f1', color: '#fff', padding: '1px 5px', borderRadius: '4px', verticalAlign: 'middle' }}>CURRENT</span>}
                        {v.version}
                      </td>
                      <td style={{ padding: '10px 12px', color: 'var(--text-secondary)' }}>{v.eksRelease}</td>
                      <td style={{ padding: '10px 12px', color: 'var(--text-secondary)' }}>{v.standardSupportEnds}</td>
                      <td style={{ padding: '10px 12px', color: 'var(--text-secondary)' }}>{v.extendedSupportEnds}</td>
                      <td style={{ padding: '10px 12px', minWidth: '140px' }}>
                        {v.tier === 'standard' ? (
                          <RunwayBar days={v.daysToStandardEnd} max={365} />
                        ) : v.tier === 'upcoming' ? (
                          <span style={{ fontSize: '11px', color: '#6366f1' }}>{t('Chystá se', 'Upcoming')}</span>
                        ) : (
                          <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>—</span>
                        )}
                      </td>
                      <td style={{ padding: '10px 12px' }}>
                        <TierBadge tier={v.tier} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {lifecycle.runwayStatus !== 'ok' && (
              <div style={{ marginTop: '14px', display: 'flex', alignItems: 'center', gap: '8px',
                padding: '10px 14px', borderRadius: '8px',
                background: lifecycle.runwayStatus === 'warn' ? '#fef9c3' : '#fee2e2',
                border: `1px solid ${lifecycle.runwayStatus === 'warn' ? '#fde047' : '#fca5a5'}` }}>
                <AlertTriangle size={14} style={{ color: lifecycle.runwayStatus === 'warn' ? '#d97706' : '#dc2626', flexShrink: 0 }} />
                <span style={{ fontSize: '12px', color: lifecycle.runwayStatus === 'warn' ? '#92400e' : '#991b1b' }}>
                  {lifecycle.runwayStatus === 'warn'
                    ? t(
                        `Pouze ${lifecycle.daysToStandardEnd} dní standard. podpory — naplánuj upgrade (ADR-0054 vyžaduje ≥${lifecycle.minRunwayDays} dní).`,
                        `Only ${lifecycle.daysToStandardEnd} days of standard support left — schedule an upgrade (ADR-0054 requires ≥${lifecycle.minRunwayDays} days).`,
                      )
                    : t(
                        'KRITICKÉ: standard podpora vypršela — každá hodina provozu stojí $0.60 (extended tier).',
                        'CRITICAL: standard support has expired — every cluster-hour costs $0.60 (extended tier penalty).',
                      )}
                </span>
              </div>
            )}
          </div>

          {/* Platform Components */}
          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '6px' }}>
              <Database size={16} style={{ color: '#6366f1' }} />
              <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>
                {t('Platformní komponenty — verze a životní cyklus', 'Platform Components — Version Lifecycle')}
              </span>
            </div>
            <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '0 0 16px' }}>
              {t(
                'Datová vrstva běží self-hosted v clusteru přes operátory (CNPG, Strimzi) dle ADR-0010 — ne AWS managed služby. Lifecycle odpočet jen tam, kde upstream publikuje EOL.',
                'The data plane is self-hosted in-cluster via operators (CNPG, Strimzi) per ADR-0010 — not AWS-managed services. Lifecycle countdown is shown only where upstream publishes an EOL.',
              )}
            </p>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '12px' }}>
              {lifecycle.components.map(svc => {
                const runway = svc.daysRemaining
                const rcolor = runway == null ? 'var(--text-tertiary)'
                  : runway > 365 ? '#16a34a' : runway > 180 ? '#d97706' : '#dc2626'
                return (
                  <div key={svc.name} style={{ padding: '14px 16px', borderRadius: '10px',
                    border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <KindIcon kind={svc.kind} />
                        <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{svc.name}</span>
                      </div>
                      <TierBadge tier={svc.tier} />
                    </div>
                    <div style={{ fontSize: '20px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.02em', marginBottom: '4px' }}>
                      v{svc.version}
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '8px' }}>{svc.managedBy}</div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <Calendar size={11} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                      {svc.standardEnd ? (
                        <>
                          <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                            {t('EOL', 'EOL')} {svc.standardEnd}
                          </span>
                          <span style={{ fontSize: '11px', fontWeight: 700, color: rcolor, marginLeft: 'auto' }}>
                            {runway != null && runway > 0 ? `${runway}d` : 'EOL'}
                          </span>
                        </>
                      ) : (
                        <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                          {t('průběžné aktualizace (operátor)', 'rolling updates (operator-managed)')}
                        </span>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          </div>

          {/* Resource Efficiency from Prometheus */}
          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <Cpu size={16} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('Efektivita JVM heapu', 'JVM Heap Efficiency')}
                </span>
              </div>
              {resources?.available && (
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  {t('Zdroj: Prometheus', 'Source: Prometheus')} · {resources.serviceCount} {t('služeb', 'services')}
                </span>
              )}
            </div>

            {!resources?.available ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '14px', borderRadius: '8px',
                background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                <Info size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                  {t(
                    'Prometheus není dostupný — metriky vytížení nejsou k dispozici. Data se zobrazí po spuštění observability stacku.',
                    'Prometheus is not reachable — resource utilisation metrics unavailable. Data will appear once the observability stack is running.',
                  )}
                </span>
              </div>
            ) : resources.services.length === 0 ? (
              <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', padding: '14px' }}>
                {t('Žádná data z Promethea (JVM metriky ještě nejsou k dispozici).', 'No data from Prometheus yet (JVM metrics not yet scraped).')}
              </div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
                  <thead>
                    <tr style={{ borderBottom: '2px solid var(--border)' }}>
                      {[
                        t('Služba', 'Service'),
                        t('Heap využití', 'Heap Usage'),
                        t('Heap max', 'Heap Max'),
                        t('CPU cores', 'CPU Cores'),
                        t('RPS', 'RPS'),
                        t('Stav', 'Status'),
                      ].map(h => (
                        <th key={h} style={{ padding: '8px 12px', textAlign: 'left',
                          color: 'var(--text-tertiary)', fontWeight: 600, fontSize: '11px' }}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {resources.services.map(svc => (
                      <tr key={svc.name} style={{ borderBottom: '1px solid var(--border)' }}>
                        <td style={{ padding: '9px 12px', fontFamily: 'monospace', fontSize: '12px', fontWeight: 500, color: 'var(--text-primary)' }}>
                          {svc.short}
                        </td>
                        <td style={{ padding: '9px 12px', minWidth: '140px' }}>
                          <HeapBar pct={svc.heap.pct} efficiency={svc.efficiency} />
                        </td>
                        <td style={{ padding: '9px 12px', color: 'var(--text-secondary)', fontSize: '11px' }}>
                          {svc.heap.maxBytes > 0 ? `${Math.round(svc.heap.maxBytes / 1048576)}MB` : '—'}
                        </td>
                        <td style={{ padding: '9px 12px', color: 'var(--text-secondary)', fontSize: '11px' }}>
                          {svc.cpuCoresUsed !== null ? svc.cpuCoresUsed.toFixed(3) : '—'}
                        </td>
                        <td style={{ padding: '9px 12px', color: 'var(--text-secondary)', fontSize: '11px' }}>
                          {svc.requestsPerSec !== null ? `${svc.requestsPerSec}/s` : '—'}
                        </td>
                        <td style={{ padding: '9px 12px' }}>
                          {svc.efficiency === 'underutilised' ? (
                            <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 7px', borderRadius: '10px', background: '#ede9fe', color: '#6366f1' }}>
                              {t('Nevytíženo', 'Underutilised')}
                            </span>
                          ) : svc.efficiency === 'high' ? (
                            <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 7px', borderRadius: '10px', background: '#fee2e2', color: '#dc2626' }}>
                              {t('Vysoké', 'High')}
                            </span>
                          ) : svc.efficiency === 'normal' ? (
                            <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 7px', borderRadius: '10px', background: '#dcfce7', color: '#16a34a' }}>
                              {t('OK', 'OK')}
                            </span>
                          ) : (
                            <span style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>—</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* AI & Agent Costs (ADR-0112 P1) */}
          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '6px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <Bot size={16} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('AI & agentní náklady', 'AI & Agent Costs')}
                </span>
                {!aiCosts?.available && (
                  <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '10px',
                    background: '#fef9c3', color: '#92400e' }}>
                    {t('Langfuse bridge není nasazen', 'Langfuse bridge not deployed')}
                  </span>
                )}
              </div>
              {aiCosts?.available && (
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  {t('Zdroj: Langfuse → Prometheus (ADR-0112 P1)', 'Source: Langfuse → Prometheus (ADR-0112 P1)')}
                  {' · '}{new Date(aiCosts.collectedAt).toLocaleTimeString(locale)}
                </span>
              )}
            </div>
            <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '0 0 16px' }}>
              {t(
                'Tokeny a náklady na AI agenty (copilot, Holmes RCA, FinOps agent). Datový zdroj: Langfuse→Prometheus bridge (ADR-0112 P1). Fallback na prázdný stav, dokud bridge není nasazen.',
                'Token usage and cost for AI agents (copilot, Holmes RCA, FinOps agent). Data source: Langfuse→Prometheus bridge (ADR-0112 P1). Falls back to empty state until bridge is deployed.',
              )}
            </p>

            {!aiCosts?.available ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '14px', borderRadius: '8px',
                background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                <Info size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                  {t(
                    'Langfuse→Prometheus bridge není nasazen — AI náklady nejsou k dispozici. Data se zobrazí po nasazení bridge (ADR-0112 P1).',
                    'Langfuse→Prometheus bridge is not deployed — AI cost metrics unavailable. Data will appear after deploying the bridge (ADR-0112 P1).',
                  )}
                </span>
              </div>
            ) : (
              <>
                {/* Hero metrics */}
                <div style={{ display: 'flex', alignItems: 'baseline', gap: '24px', marginBottom: '20px',
                  padding: '14px 18px', borderRadius: '10px', background: 'rgba(99,102,241,0.05)', border: '1px solid rgba(99,102,241,0.2)' }}>
                  <div>
                    <div style={{ fontSize: '32px', fontWeight: 900, color: '#6366f1', letterSpacing: '-0.04em', lineHeight: 1 }}>
                      ${aiCosts.totalCostLast7dUsd.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>
                      {t('Celkem AI náklady — posledních 7 dní', 'Total AI costs — last 7 days')}
                    </div>
                  </div>
                  {aiCosts.selfHostedPct == null ? (
                    <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                      {t('Rozdělení podle poskytovatele bridge neexportuje.', 'Provider split is not exported by the bridge.')}
                    </span>
                  ) : (
                    <>
                      <span style={{ fontSize: '11px', fontWeight: 700, padding: '3px 10px', borderRadius: '20px',
                        background: '#dcfce7', color: '#16a34a' }}>
                        {aiCosts.selfHostedPct}% self-hosted vLLM
                      </span>
                      <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                        {100 - aiCosts.selfHostedPct}% Anthropic API
                      </span>
                    </>
                  )}
                </div>
                <div role="status" style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '-10px 0 16px' }}>
                  {t('Pokrytí', 'Coverage')}: {aiCosts.coverage.windows['7d'].availableHours}h / 7d
                  {' · '}{t('retence', 'retention')} {aiCosts.coverage.retentionHours}h
                  {' · '}{t('poslední úspěšné načtení', 'last successful load')}:{' '}
                  {aiCosts.coverage.lastSuccessfulLoad
                    ? new Date(aiCosts.coverage.lastSuccessfulLoad).toLocaleString(locale)
                    : t('žádné', 'none')}
                </div>

                {/* Per-agent rows */}
                {aiCosts.agents.length === 0 ? (
                  <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', padding: '10px' }}>
                    {t('Žádná agentní data zatím.', 'No agent data yet.')}
                  </div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', marginBottom: '20px' }}>
                    <div style={{ display: 'grid', gridTemplateColumns: '160px 100px 1fr 90px 90px', gap: '12px',
                      padding: '4px 8px', borderBottom: '1px solid var(--border)' }}>
                      {[t('Agent', 'Agent'), t('Burn rate', 'Burn rate'), t('Budget', 'Budget'), t('Náklady 24h', 'Cost 24h'), t('Náklady 7d', 'Cost 7d')].map(h => (
                        <span key={h} style={{ fontSize: '10px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</span>
                      ))}
                    </div>
                    {aiCosts.agents.map(agent => {
                      const burnCfg = {
                        low:      { color: '#16a34a', bg: '#dcfce7', label: t('Nízký', 'Low') },
                        normal:   { color: '#2563eb', bg: '#dbeafe', label: t('Normální', 'Normal') },
                        high:     { color: '#d97706', bg: '#fef3c7', label: t('Vysoký', 'High') },
                        exceeded: { color: '#dc2626', bg: '#fee2e2', label: t('Překročen', 'Exceeded') },
                      }[agent.burnRate]
                      const budgetPct = agent.budgetUsedPct
                      const budgetColor = budgetPct == null ? 'var(--text-tertiary)'
                        : budgetPct > 100 ? '#dc2626'
                        : budgetPct > 80  ? '#d97706'
                        : '#16a34a'
                      return (
                        <div key={agent.agentId} style={{ display: 'grid', gridTemplateColumns: '160px 100px 1fr 90px 90px', gap: '12px',
                          alignItems: 'center', padding: '8px', borderRadius: '8px', background: 'var(--surface-2)' }}>
                          <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)', fontFamily: 'monospace' }}>
                            {agent.agentId}
                          </span>
                          <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '10px',
                            color: burnCfg.color, background: burnCfg.bg, display: 'inline-block' }}>
                            {burnCfg.label}
                          </span>
                          <div>
                            {budgetPct != null ? (
                              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                <div style={{ flex: 1, height: '5px', background: 'var(--surface-3)', borderRadius: '3px', overflow: 'hidden', maxWidth: '120px' }}>
                                  <div style={{ width: `${Math.min(budgetPct, 100)}%`, height: '100%', background: budgetColor, borderRadius: '3px' }} />
                                </div>
                                <span style={{ fontSize: '11px', fontWeight: 700, color: budgetColor }}>{budgetPct.toFixed(0)}%</span>
                              </div>
                            ) : (
                              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>—</span>
                            )}
                          </div>
                          <span style={{ fontSize: '12px', fontFamily: 'monospace', color: 'var(--text-primary)', textAlign: 'right' }}>
                            ${agent.costLast24hUsd.toFixed(2)}
                          </span>
                          <span style={{ fontSize: '12px', fontFamily: 'monospace', color: 'var(--text-primary)', textAlign: 'right' }}>
                            ${agent.costLast7dUsd.toFixed(2)}
                          </span>
                        </div>
                      )
                    })}
                  </div>
                )}
              </>
            )}
          </div>

          {/* Right-sizing panel — VPA recommendations + k8s resource efficiency */}
          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '6px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <TrendingDown size={16} style={{ color: '#d97706' }} />
                <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('Right-sizing — efektivita zdrojů Kubernetes', 'Right-sizing — Kubernetes Resource Efficiency')}
                </span>
              </div>
              {rightSizing?.available && (
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  {t('Zdroj: Prometheus (kube-state-metrics + cAdvisor)', 'Source: Prometheus (kube-state-metrics + cAdvisor)')}
                  {rightSizing.fleetCpuEfficiencyPct != null && ` · ${t('průměr CPU', 'avg CPU')}: ${rightSizing.fleetCpuEfficiencyPct}%`}
                </span>
              )}
            </div>
            <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '0 0 16px' }}>
              {t(
                'Porovnání skutečného využití CPU/paměti s tím, co je nakonfigurováno v requests. VPA (ADR-0099, updateMode: Off) plní doporučení bez dotknutí se podů.',
                'Actual CPU/memory usage vs. k8s requests. VPA (ADR-0099, updateMode: Off) fills recommendations without touching pods.',
              )}
            </p>

            {!rightSizing?.available ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '14px', borderRadius: '8px',
                background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                <Info size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                  {t(
                    'Prometheus není dostupný nebo VPA ještě nemá dostatečná data.',
                    'Prometheus unreachable or VPA has no data yet (allow ≥1 h after install).',
                  )}
                </span>
              </div>
            ) : rightSizing.services.length === 0 ? (
              <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', padding: '14px' }}>
                {t('Žádná data — VPA/kube-state-metrics ještě nejsou k dispozici.', 'No data — VPA / kube-state-metrics not yet scraped.')}
              </div>
            ) : (
              <>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '8px 12px', borderRadius: '6px',
                  background: 'var(--surface-2)', border: '1px solid var(--border)', marginBottom: '14px' }}>
                  <Info size={12} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                  <span style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                    {rightSizing.vpaHasData
                      ? t('VPA doporučení dostupná — zobrazena ve sloupci VPA.', 'VPA recommendations available — shown in the VPA column.')
                      : t('VPA zatím nemá data (CRDs instalovány; počkejte ≥1 h po instalaci).', 'VPA not yet providing recommendations (CRDs installed; allow ≥1 h for initial data).')}
                  </span>
                </div>
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
                    <thead>
                      <tr style={{ borderBottom: '2px solid var(--border)' }}>
                        {[
                          t('Namespace', 'Namespace'),
                          t('CPU rekvizice (m)', 'CPU Request (m)'),
                          t('CPU využití (m)', 'CPU Used (m)'),
                          t('CPU efektivita', 'CPU Efficiency'),
                          t('Paměť req (MiB)', 'Mem Request (MiB)'),
                          t('Paměť využití (MiB)', 'Mem Used (MiB)'),
                          t('Mem efektivita', 'Mem Efficiency'),
                          t('VPA CPU (m)', 'VPA CPU (m)'),
                          t('VPA Mem (MiB)', 'VPA Mem (MiB)'),
                          t('Potenciál', 'Potential'),
                        ].map(h => (
                          <th key={h} style={{ padding: '8px 10px', textAlign: 'left',
                            color: 'var(--text-tertiary)', fontWeight: 600, fontSize: '10px', whiteSpace: 'nowrap' }}>{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {rightSizing.services.map(svc => {
                        const potColor = svc.savingsPotential === 'high' ? '#dc2626'
                          : svc.savingsPotential === 'medium' ? '#d97706'
                          : svc.savingsPotential === 'low' ? '#16a34a'
                          : 'var(--text-tertiary)'
                        const potBg = svc.savingsPotential === 'high' ? '#fee2e2'
                          : svc.savingsPotential === 'medium' ? '#fef3c7'
                          : svc.savingsPotential === 'low' ? '#dcfce7'
                          : 'var(--surface-2)'
                        const potLabel = svc.savingsPotential === 'high'
                          ? t('Vysoký', 'High')
                          : svc.savingsPotential === 'medium'
                          ? t('Střední', 'Medium')
                          : svc.savingsPotential === 'low'
                          ? t('Nízký', 'Low')
                          : '—'
                        const effBar = (pct: number | null) => {
                          if (pct === null) return <span style={{ color: 'var(--text-tertiary)' }}>—</span>
                          const c = pct < 30 ? '#dc2626' : pct < 60 ? '#d97706' : '#16a34a'
                          return (
                            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                              <div style={{ width: '50px', height: '4px', background: 'var(--surface-3)', borderRadius: '2px', overflow: 'hidden' }}>
                                <div style={{ width: `${Math.min(pct, 100)}%`, height: '100%', background: c, borderRadius: '2px' }} />
                              </div>
                              <span style={{ fontSize: '11px', fontWeight: 700, color: c }}>{pct}%</span>
                            </div>
                          )
                        }
                        return (
                          <tr key={svc.namespace} style={{ borderBottom: '1px solid var(--border)' }}>
                            <td style={{ padding: '8px 10px', fontFamily: 'monospace', fontWeight: 600, color: 'var(--text-primary)', fontSize: '12px' }}>
                              {svc.displayName}
                            </td>
                            <td style={{ padding: '8px 10px', color: 'var(--text-secondary)', fontSize: '11px', fontFamily: 'monospace' }}>
                              {svc.cpu.requestedMillicores ?? '—'}
                            </td>
                            <td style={{ padding: '8px 10px', color: 'var(--text-secondary)', fontSize: '11px', fontFamily: 'monospace' }}>
                              {svc.cpu.usedMillicores ?? '—'}
                            </td>
                            <td style={{ padding: '8px 10px', minWidth: '100px' }}>
                              {effBar(svc.cpu.efficiencyPct)}
                            </td>
                            <td style={{ padding: '8px 10px', color: 'var(--text-secondary)', fontSize: '11px', fontFamily: 'monospace' }}>
                              {svc.memory.requestedMiB ?? '—'}
                            </td>
                            <td style={{ padding: '8px 10px', color: 'var(--text-secondary)', fontSize: '11px', fontFamily: 'monospace' }}>
                              {svc.memory.usedMiB ?? '—'}
                            </td>
                            <td style={{ padding: '8px 10px', minWidth: '100px' }}>
                              {effBar(svc.memory.efficiencyPct)}
                            </td>
                            <td style={{ padding: '8px 10px', color: svc.vpaRecommendation.cpuMillicores != null ? '#6366f1' : 'var(--text-tertiary)',
                              fontSize: '11px', fontFamily: 'monospace', fontWeight: svc.vpaRecommendation.cpuMillicores != null ? 700 : 400 }}>
                              {svc.vpaRecommendation.cpuMillicores ?? '—'}
                            </td>
                            <td style={{ padding: '8px 10px', color: svc.vpaRecommendation.memoryMiB != null ? '#6366f1' : 'var(--text-tertiary)',
                              fontSize: '11px', fontFamily: 'monospace', fontWeight: svc.vpaRecommendation.memoryMiB != null ? 700 : 400 }}>
                              {svc.vpaRecommendation.memoryMiB ?? '—'}
                            </td>
                            <td style={{ padding: '8px 10px' }}>
                              {svc.savingsPotential !== 'unknown' ? (
                                <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 7px', borderRadius: '10px',
                                  color: potColor, background: potBg }}>{potLabel}</span>
                              ) : (
                                <span style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>—</span>
                              )}
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>

          {/* ADR reference + cost math explainer */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
            <div style={{ padding: '16px 20px', borderRadius: 'var(--r-lg)', border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
                <DollarSign size={14} style={{ color: '#16a34a' }} />
                <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('Kalkulace nákladů', 'Cost Calculation')}
                </span>
              </div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.6 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px' }}>
                  <span>{t('Aktuální sazba (standard)', 'Current rate (standard)')}</span>
                  <span style={{ fontWeight: 600, fontFamily: 'monospace' }}>$0.10/hr</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px' }}>
                  <span>{t('Extended tier sazba', 'Extended tier rate')}</span>
                  <span style={{ fontWeight: 600, fontFamily: 'monospace', color: '#dc2626' }}>$0.60/hr</span>
                </div>
                <div style={{ borderTop: '1px solid var(--border)', marginTop: '8px', paddingTop: '8px' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px' }}>
                    <span>{t('Měsíční odhad', 'Monthly estimate')}</span>
                    <span style={{ fontWeight: 700, color: 'var(--text-primary)', fontFamily: 'monospace' }}>
                      ${lifecycle.monthlyEstimate.toLocaleString(locale)}
                    </span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span>{t('Roční úspora vs. extended', 'Annual savings vs extended')}</span>
                    <span style={{ fontWeight: 700, color: '#16a34a', fontFamily: 'monospace' }}>
                      +${lifecycle.annualSavingsVsExtended.toLocaleString(locale)}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            <div style={{ padding: '16px 20px', borderRadius: 'var(--r-lg)', border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
                <Info size={14} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('Governance', 'Governance')}
                </span>
              </div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.6 }}>
                <div style={{ marginBottom: '6px' }}>
                  <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>ADR-0054</span>
                  {' — '}{t('FinOps guardraily: verze spravovaných služeb a nákladový audit.', 'FinOps guardrails: managed-service version lifecycle and periodic cost audit.')}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '3px' }}>
                  {[
                    t('✓ Vždy N-1 (ne latest)', '✓ Always N-1 (not latest)'),
                    t('✓ ≥6 měsíců standard podpora', '✓ ≥6 months standard support runway'),
                    t('✓ Týdenní audit CI workflow', '✓ Weekly audit via CI workflow'),
                    t('○ Live cost anomaly detection (plánováno)', '○ Live cost anomaly detection (planned)'),
                  ].map(rule => (
                    <span key={rule} style={{ fontSize: '11px', color: rule.startsWith('✓') || rule.startsWith('○') ? 'var(--text-secondary)' : 'var(--text-tertiary)' }}>
                      {rule}
                    </span>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </>
      ) : null}
    </div>
  )
}

export default function FinOpsPage() {
  return (
    <AuthGuard permission="system:view">
      <FinOpsContent />
    </AuthGuard>
  )
}
