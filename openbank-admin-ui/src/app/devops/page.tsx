// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import {
  GitBranch, RefreshCw, Rocket, Clock, AlertTriangle, Wrench,
  Info, Zap,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useAuth } from '@/lib/auth/useAuth'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { AgentInsightsPanel } from '@/components/agent/AgentInsightsPanel'
import type { AgentFinding } from '@/components/agent/AgentInsightsPanel'
import { QualityGateHealthPanel } from '@/components/devops/QualityGateHealthPanel'
import type { DevOpsFinding } from '@/app/api/devops/insights/route'
import { PageHeader } from '@/components/ui/PageHeader'

// ── Types ─────────────────────────────────────────────────────────────────────

type DoraLevel = 'elite' | 'high' | 'medium' | 'low' | null

interface DoraMetric {
  perDay?: number | null
  count30d?: number
  hours?: number | null
  pct?: number | null
  level: DoraLevel
  description: string | null
  note?: string
}

interface DoraData {
  overall: DoraLevel
  metrics: {
    deploymentFrequency: DoraMetric
    leadTime: DoraMetric
    changeFailureRate: DoraMetric
    mttr: DoraMetric
  }
  recentDeployments: { date: string; service: string; sha: string }[]
  sources: { git: boolean; prometheus: boolean }
  collectedAt: string
}

// ── DORA level colours + labels ───────────────────────────────────────────────

const DORA_CFG: Record<NonNullable<DoraLevel>, { color: string; bg: string; border: string; label: string; labelCs: string }> = {
  elite:  { color: '#16a34a', bg: '#dcfce7', border: '#86efac', label: 'Elite',  labelCs: 'Elite' },
  high:   { color: '#2563eb', bg: '#dbeafe', border: '#93c5fd', label: 'High',   labelCs: 'Vysoký' },
  medium: { color: '#d97706', bg: '#fef9c3', border: '#fde047', label: 'Medium', labelCs: 'Střední' },
  low:    { color: '#dc2626', bg: '#fee2e2', border: '#fca5a5', label: 'Low',    labelCs: 'Nízký' },
}

// ── Helper components ─────────────────────────────────────────────────────────

function DoraLevelBadge({ level, large }: { level: DoraLevel; large?: boolean }) {
  const { t } = useLanguage()
  if (!level) return <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>—</span>
  const c = DORA_CFG[level]
  return (
    <span style={{
      fontSize: large ? '12px' : '10px',
      fontWeight: 800,
      padding: large ? '4px 12px' : '2px 8px',
      borderRadius: '10px',
      background: c.bg,
      color: c.color,
      border: `1px solid ${c.border}`,
      letterSpacing: '0.05em',
      textTransform: 'uppercase',
    }}>
      {t(c.labelCs, c.label)}
    </span>
  )
}

function DoraCard({ icon, titleEn, titleCs, value, sub, level, note, noDataMsg }: {
  icon: React.ReactNode
  titleEn: string; titleCs: string
  value: string | null
  sub: string | null
  level: DoraLevel
  note?: string
  noDataMsg?: string
}) {
  const { t } = useLanguage()
  const color = level ? DORA_CFG[level].color : 'var(--text-secondary)'

  return (
    <div style={{
      background: 'var(--surface)',
      border: level ? `1px solid ${DORA_CFG[level].border}` : '1px solid var(--border)',
      borderRadius: 'var(--r-lg)',
      padding: '20px 22px',
      display: 'flex', flexDirection: 'column', gap: '12px',
    }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <div style={{ width: '36px', height: '36px', borderRadius: '10px',
          background: level ? `${DORA_CFG[level].color}18` : 'var(--surface-2)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', color }}>
          {icon}
        </div>
        <DoraLevelBadge level={level} large />
      </div>

      <div>
        <div style={{ fontSize: '28px', fontWeight: 800, color: value ? color : 'var(--text-tertiary)',
          letterSpacing: '-0.04em', marginBottom: '4px' }}>
          {value ?? '—'}
        </div>
        <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '2px' }}>
          {t(titleCs, titleEn)}
        </div>
        {sub && <div style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>{sub}</div>}
        {!value && noDataMsg && (
          <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '4px' }}>{noDataMsg}</div>
        )}
      </div>

      {note && (
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: '6px', paddingTop: '8px',
          borderTop: '1px solid var(--border)' }}>
          <Info size={11} style={{ color: 'var(--text-tertiary)', marginTop: '1px', flexShrink: 0 }} />
          <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', lineHeight: 1.5 }}>{note}</span>
        </div>
      )}
    </div>
  )
}

function SourceChip({ available, label }: { available: boolean; label: string }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px',
      fontSize: '10px', fontWeight: 600, padding: '2px 8px', borderRadius: '10px',
      background: available ? '#dcfce7' : 'var(--surface-3)',
      color: available ? '#16a34a' : 'var(--text-tertiary)',
      border: `1px solid ${available ? '#86efac' : 'var(--border)'}` }}>
      <span style={{ width: '5px', height: '5px', borderRadius: '50%', background: available ? '#16a34a' : 'var(--text-tertiary)', flexShrink: 0 }} />
      {label}
    </span>
  )
}

// ── DevOps Insights (AI) — ADR-0119 ───────────────────────────────────────────

// Short bilingual label for the DORA metric a finding impacts.
const DORA_METRIC_LABEL: Record<NonNullable<DevOpsFinding['doraMetricImpacted']>, { cs: string; en: string }> = {
  DEPLOYMENT_FREQUENCY:  { cs: 'Frekvence nasazení', en: 'Deployment freq.' },
  LEAD_TIME_FOR_CHANGES: { cs: 'Průběžná doba',       en: 'Lead time' },
  CHANGE_FAILURE_RATE:   { cs: 'Chybovost změn',      en: 'Change failure' },
  TIME_TO_RESTORE:       { cs: 'Doba obnovy',         en: 'Time to restore' },
}

// Map a devops-agent finding → the shared AgentFinding view-model. Severity is
// lower-cased (the backend emits WARNING/CRITICAL); the DORA-impact and
// remediation-kind become tags so the rendering matches every other agent surface.
function toAgentFinding(f: DevOpsFinding, t: (cs: string, en: string) => string): AgentFinding {
  const tags: AgentFinding['tags'] = []
  if (f.doraMetricImpacted) {
    const d = DORA_METRIC_LABEL[f.doraMetricImpacted]
    tags.push({ label: t(`DORA: ${d.cs}`, `DORA: ${d.en}`), tone: 'cyan' })
  }
  if (f.remediationKind !== 'NONE') {
    tags.push({ label: f.remediationKind, tone: 'neutral' })
  }
  return {
    id: f.id,
    title: f.title,
    detector: f.detector,
    severity: f.severity === 'CRITICAL' ? 'critical' : 'warning',
    status: f.status,
    rootCause: f.rootCause,
    detectedAt: f.detectedAt,
    proposalUrl: f.proposalPrUrl,
    proposalLabel: t('Zobrazit návrh →', 'View proposal →'),
    tags,
  }
}

// ── Main page ─────────────────────────────────────────────────────────────────

function DevOpsContent() {
  const { t, language } = useLanguage()
  const { hasPermission } = useAuth()
  const canDecide = hasPermission('devops:decide')
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [dora, setDora] = useState<DoraData | null>(null)
  const [findings, setFindings] = useState<DevOpsFinding[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const [deciding, setDeciding] = useState<string | null>(null)
  const [decisionError, setDecisionError] = useState<string | null>(null)
  const [pendingDecision, setPendingDecision] = useState<{ finding: DevOpsFinding; action: 'approve' | 'reject' } | null>(null)
  const decisionCancelRef = useRef<HTMLButtonElement>(null)
  const decisionConfirmRef = useRef<HTMLButtonElement>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const [doraRes, insightsRes] = await Promise.all([
        fetch('/api/devops/dora', { cache: 'no-store' }),
        fetch('/api/devops/insights', { cache: 'no-store' }),
      ])

      if (!doraRes.ok) { setUnavailable({ kind: 'error' }); return }

      setDora(await doraRes.json())
      if (insightsRes.ok) {
        const ins = await insightsRes.json() as { findings?: DevOpsFinding[] }
        setFindings(ins.findings ?? [])
      }
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
      setLastRefresh(new Date())
    }
  }, [])

  // HITL decision — an operator approves/rejects a proposed remediation (ADR-0031 D4). On success the
  // finding leaves the active list, so we just refresh. Write action: degrade quietly per the
  // graceful-state rule (no raw HTTP status in the UI).
  const decide = useCallback(async (id: string, action: 'approve' | 'reject') => {
    setDeciding(id)
    setDecisionError(null)
    try {
      const res = await fetch('/api/devops/decide', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, action }),
      })
      if (!res.ok) {
        setDecisionError(t(
          'Rozhodnutí se nepodařilo uložit. Pravděpodobně nemáte oprávnění nebo je služba nedostupná.',
          'The decision could not be saved. You may not have permission or the service is unavailable.',
        ))
        return
      }
      setPendingDecision(null)
      await load()
    } catch {
      setDecisionError(t('Služba DevOps neodpovídá. Zkuste to prosím znovu.', 'The DevOps service did not respond. Please try again.'))
    } finally {
      setDeciding(null)
    }
  }, [load, t])

  useEffect(() => { load() }, [load])
  useEffect(() => {
    const id = setInterval(load, 60_000)
    return () => clearInterval(id)
  }, [load])

  if (unavailable) {
    return <DataUnavailable kind={unavailable.kind} service="devops" feature={t('DevOps přehled', 'DevOps overview')} lang={language} />
  }

  const df = dora?.metrics.deploymentFrequency
  const lt = dora?.metrics.leadTime
  const cfr = dora?.metrics.changeFailureRate
  const mttr = dora?.metrics.mttr

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>

      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">DevOps</span></div>}
        icon={<GitBranch size={20} aria-hidden="true" />}
        title={t('DevOps metriky', 'DevOps Metrics')}
        subtitle={t('DORA metriky, pokrytí testy a zdraví pipeline — Google State of DevOps 2023', 'DORA metrics, test coverage, and pipeline health — Google State of DevOps 2023')}
        actions={<div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
          {dora && (
            <div style={{ display: 'flex', gap: '6px' }}>
              <SourceChip available={dora.sources.git} label="Git" />
              <SourceChip available={dora.sources.prometheus} label="Prometheus" />
            </div>
          )}
          {lastRefresh && (
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
              {lastRefresh.toLocaleTimeString(dateLocale)}
            </span>
          )}
          <button
            type="button"
            onClick={load}
            disabled={loading}
            aria-busy={loading}
            aria-label={t('Obnovit DevOps metriky', 'Refresh DevOps metrics')}
            style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '7px 14px',
              borderRadius: '8px', border: '1px solid var(--border)', background: 'var(--surface)',
              color: 'var(--text-secondary)', fontSize: '12px', cursor: loading ? 'wait' : 'pointer' }}
          >
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      {loading && !dora ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={16} style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '13px' }}>{t('Načítám DORA metriky…', 'Loading DORA metrics…')}</span>
        </div>
      ) : (
        <>
          {/* Overall DORA score */}
          {dora && dora.overall && (
            <div style={{ marginBottom: '24px', padding: '16px 20px', borderRadius: 'var(--r-lg)',
              border: `1px solid ${DORA_CFG[dora.overall].border}`,
              background: `${DORA_CFG[dora.overall].color}08`,
              display: 'flex', alignItems: 'center', gap: '14px' }}>
              <Zap size={20} style={{ color: DORA_CFG[dora.overall].color, flexShrink: 0 }} />
              <div style={{ flex: 1 }}>
                <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('Celková DORA úroveň', 'Overall DORA Performance')}:{' '}
                </span>
                <DoraLevelBadge level={dora.overall} large />
              </div>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                {t('Nejnižší ze změřených metrik', 'Lowest of measured metrics')}
              </span>
            </div>
          )}

          {/* DORA 4 metrics */}
          <div className="grid-4" style={{ marginBottom: '28px' }}>
            <DoraCard
              icon={<Rocket size={18} />}
              titleEn="Deployment Frequency"
              titleCs="Frekvence nasazení"
              value={df?.description ?? null}
              sub={df?.count30d != null ? t(`${df.count30d} nasazení za 30 dní`, `${df.count30d} deployments in last 30 days`) : null}
              level={df?.level ?? null}
              noDataMsg={t('Odvozeno z git historie', 'Derived from git history')}
            />
            <DoraCard
              icon={<Clock size={18} />}
              titleEn="Lead Time for Changes"
              titleCs="Průběžná doba změny"
              value={lt?.description ?? null}
              sub={t('Medián: commit → trunk', 'Median: commit → trunk')}
              level={lt?.level ?? null}
              note={lt?.note}
              noDataMsg={t('Roadmapa: korelace s deploy eventem (ADR-0061 fáze 2)', 'Roadmap: deploy-event correlation (ADR-0061 phase 2)')}
            />
            <DoraCard
              icon={<AlertTriangle size={18} />}
              titleEn="Change Failure Rate"
              titleCs="Chybovost nasazení"
              value={cfr?.pct != null ? `${cfr.pct}%` : null}
              sub={t('30denní 5xx chybová sazba (proxy)', '30-day 5xx error rate (proxy)')}
              level={cfr?.level ?? null}
              note={cfr?.note}
              noDataMsg={t('Vyžaduje Prometheus', 'Requires Prometheus')}
            />
            <DoraCard
              icon={<Wrench size={18} />}
              titleEn="Mean Time to Restore"
              titleCs="Průměrná doba obnovy"
              value={mttr?.hours != null ? `${mttr.hours}h` : null}
              sub={t('Čas od incidentu do obnovy služby', 'Time from incident to service restore')}
              level={mttr?.level ?? null}
              note={mttr?.note}
              noDataMsg={t('Roadmapa: z ICT incident registru (ADR-0061 fáze 3)', 'Roadmap: from the ICT incident register (ADR-0061 phase 3)')}
            />
          </div>

          {/* ── CI quality-gate health — ADR-0254/0253 — self-fetching, degrades quietly ── */}
          <QualityGateHealthPanel />

          {/* ── DevOps Insights (AI) — ADR-0119 — shared AgentInsightsPanel (admin-ui agent-output rule) ── */}
          <AgentInsightsPanel
            title={t('DevOps náhledy (AI)', 'DevOps Insights (AI)')}
            subtitle={t(
              'Aktivní nálezy z devops-agenta (CI zdraví, DORA regrese, kapacita runnerů, deploy, SSDLC, opakované incidenty — ADR-0119). Agent navrhuje, schvaluje člověk (HITL, ADR-0031 D4).',
              'Active findings from the devops-agent (CI health, DORA regressions, runner capacity, deploys, SSDLC, incident recurrence — ADR-0119). The agent proposes; a human approves (HITL, ADR-0031 D4).',
            )}
            findings={findings.map(f => toAgentFinding(f, t))}
            emptyMessage={t('Žádné aktivní DevOps nálezy — pipeline v pořádku', 'No active DevOps findings — pipeline healthy')}
            onApprove={canDecide ? id => {
              const finding = findings.find(candidate => candidate.id === id)
              if (finding) { setDecisionError(null); setPendingDecision({ finding, action: 'approve' }) }
            } : undefined}
            onReject={canDecide ? id => {
              const finding = findings.find(candidate => candidate.id === id)
              if (finding) { setDecisionError(null); setPendingDecision({ finding, action: 'reject' }) }
            } : undefined}
            decideLabels={canDecide ? { approve: t('Schválit', 'Approve'), reject: t('Odmítnout', 'Reject') } : undefined}
            decidingId={canDecide ? deciding : null}
          />
          {decisionError && (
            <div role="alert" style={{ marginBottom: '20px', padding: '12px 16px', borderRadius: '8px', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', color: 'var(--danger-text)', fontSize: '12px' }}>
              {decisionError}
            </div>
          )}

          {pendingDecision && (
            <div
              role="alertdialog"
              aria-modal="true"
              aria-labelledby="devops-decision-review-title"
              aria-describedby="devops-decision-review-impact"
              onKeyDown={event => {
                if (event.key === 'Escape' && deciding !== pendingDecision.finding.id) {
                  setPendingDecision(null)
                  setDecisionError(null)
                }
                if (event.key === 'Tab') {
                  const first = decisionCancelRef.current
                  const last = decisionConfirmRef.current
                  if (event.shiftKey && document.activeElement === first) {
                    event.preventDefault()
                    last?.focus()
                  } else if (!event.shiftKey && document.activeElement === last) {
                    event.preventDefault()
                    first?.focus()
                  }
                }
              }}
              style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.68)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20 }}
            >
              <div className="card" style={{ width: 'min(620px, 100%)', maxHeight: '90vh', overflowY: 'auto', padding: 22 }}>
                <h2 id="devops-decision-review-title" style={{ margin: 0, fontSize: 17 }}>
                  {pendingDecision.action === 'approve' ? t('Zkontrolovat schválení remediace', 'Review remediation approval') : t('Zkontrolovat odmítnutí remediace', 'Review remediation rejection')}
                </h2>
                <p id="devops-decision-review-impact" style={{ fontSize: 13, lineHeight: 1.55, color: 'var(--text-secondary)' }}>
                  {pendingDecision.action === 'approve'
                    ? t('Schválením povolíte navrženou operátorskou remediaci. Samotný nález vytvořil AI agent; rozhodnutí a odpovědnost zůstávají na člověku.', 'Approval authorizes the proposed operator remediation. The finding was produced by an AI agent; the decision and accountability remain human.')
                    : t('Odmítnutím uzavřete tento návrh bez schválení remediace.', 'Rejection closes this proposal without authorizing the remediation.')}
                </p>
                <dl style={{ display: 'grid', gridTemplateColumns: '145px minmax(0, 1fr)', gap: '8px 12px', padding: 14, borderRadius: 8, background: 'var(--surface-2)', fontSize: 12 }}>
                  <dt>{t('Nález', 'Finding')}</dt><dd>{pendingDecision.finding.title}</dd>
                  <dt>ID</dt><dd className="mono" style={{ overflowWrap: 'anywhere' }}>{pendingDecision.finding.id}</dd>
                  <dt>{t('Detektor', 'Detector')}</dt><dd className="mono">{pendingDecision.finding.detector}</dd>
                  <dt>{t('Závažnost', 'Severity')}</dt><dd>{pendingDecision.finding.severity}</dd>
                  <dt>{t('Stav', 'Status')}</dt><dd>{pendingDecision.finding.status}</dd>
                  <dt>{t('Dotčený zdroj', 'Affected resource')}</dt><dd>{pendingDecision.finding.affectedResource}</dd>
                  <dt>{t('Typ remediace', 'Remediation kind')}</dt><dd>{pendingDecision.finding.remediationKind}</dd>
                  <dt>{t('DORA dopad', 'DORA impact')}</dt><dd>{pendingDecision.finding.doraMetricImpacted ?? t('Neuveden', 'Not specified')}</dd>
                  <dt>{t('Kořenová příčina', 'Root cause')}</dt><dd>{pendingDecision.finding.rootCause ?? t('Neuvedena', 'Not specified')}</dd>
                  <dt>{t('Navržená remediace', 'Proposed remediation')}</dt><dd>{pendingDecision.finding.proposedRemediation ?? t('Neuvedena', 'Not specified')}</dd>
                </dl>
                {pendingDecision.finding.proposalPrUrl && <p style={{ fontSize: 12 }}><a href={pendingDecision.finding.proposalPrUrl} target="_blank" rel="noopener noreferrer">{t('Otevřít návrh v novém panelu', 'Open proposal in a new tab')} →</a></p>}
                {decisionError && <div role="alert" data-testid="devops-decision-review-error" style={{ padding: 10, borderLeft: '3px solid var(--danger)', color: 'var(--danger)', fontSize: 12 }}>{decisionError}</div>}
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
                  <button ref={decisionCancelRef} autoFocus type="button" className="btn btn-secondary" disabled={deciding === pendingDecision.finding.id} onClick={() => { setPendingDecision(null); setDecisionError(null) }}>{t('Zpět', 'Back')}</button>
                  <button ref={decisionConfirmRef} type="button" className="btn btn-primary" aria-busy={deciding === pendingDecision.finding.id} disabled={deciding === pendingDecision.finding.id} onClick={() => void decide(pendingDecision.finding.id, pendingDecision.action)}>
                    {deciding === pendingDecision.finding.id ? t('Odesílám…', 'Submitting…') : pendingDecision.action === 'approve' ? t('Potvrdit schválení', 'Confirm approval') : t('Potvrdit odmítnutí', 'Confirm rejection')}
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Data source guidance */}
          {dora && (!dora.sources.git || !dora.sources.prometheus) && (
            <div style={{ marginBottom: '20px', padding: '14px 18px', borderRadius: '10px',
              border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
                <Info size={14} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('Konfigurace datových zdrojů', 'Data Source Configuration')}
                </span>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                {!dora.sources.git && (
                  <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                    <span style={{ fontFamily: 'monospace', background: 'var(--surface-3)', padding: '1px 5px', borderRadius: '4px' }}>dora.json</span>
                    {' — '}{t('git-derived snapshot (collect-dora.mjs) chybí; Deployment Frequency se objeví po build/deploy. ADR-0061.', 'git-derived snapshot (collect-dora.mjs) missing; Deployment Frequency appears after a build/deploy. ADR-0061.')}
                  </span>
                )}
                {!dora.sources.prometheus && (
                  <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                    {t('Prometheus není dostupný — Change Failure Rate není k dispozici.', 'Prometheus is not reachable — Change Failure Rate unavailable.')}
                  </span>
                )}
              </div>
            </div>
          )}

          {/* Recent deployments */}
          {dora && dora.recentDeployments.length > 0 && (
            <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)',
              padding: '20px 24px', marginBottom: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '14px' }}>
                <GitBranch size={16} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('Poslední nasazení', 'Recent Deployments')}
                </span>
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  {t('Squash-merge do main (posledních 30 dní)', 'Squash-merges to main (last 30 days)')}
                </span>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '2px' }}>
                {dora.recentDeployments.slice(0, 8).map((d, i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'center', gap: '12px',
                    padding: '6px 0', borderBottom: i < dora.recentDeployments.length - 1 ? '1px solid var(--border)' : 'none' }}>
                    <span style={{ fontSize: '10px', fontFamily: 'monospace', color: '#6366f1',
                      background: '#ede9fe', padding: '1px 6px', borderRadius: '4px', flexShrink: 0 }}>
                      {d.sha}
                    </span>
                    <span style={{ fontSize: '12px', fontFamily: 'monospace', color: 'var(--text-secondary)',
                      background: 'var(--surface-2)', padding: '1px 8px', borderRadius: '6px', flexShrink: 0 }}>
                      {d.service || '—'}
                    </span>
                    <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginLeft: 'auto', flexShrink: 0 }}>
                      {new Date(d.date).toLocaleDateString(language === 'cs' ? 'cs-CZ' : 'en-GB')}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Test evidence has one authoritative home.  Do not duplicate a partial JUnit-only
              projection here: it made DevOps and Test Intelligence disagree about the same run. */}
          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 18, flexWrap: 'wrap' }}>
            <div>
              <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{t('Test Intelligence', 'Test Intelligence')}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: 5 }}>{t('Autoritativní pohled na CI běhy, runtime Testcontainers, pokrytí, trace kontrakty, mutace, výkon, syntetiku, klientské E2E a RUM.', 'The authoritative view for CI runs, Testcontainers runtime, coverage, trace contracts, mutation, performance, synthetics, client E2E and RUM.')}</div>
            </div>
            <a href="/system/tests" className="btn btn-secondary btn-sm">{t('Otevřít Test Intelligence', 'Open Test Intelligence')} →</a>
          </div>

          {/* DORA reference */}
          <div style={{ padding: '16px 20px', borderRadius: 'var(--r-lg)', border: '1px solid var(--border)',
            background: 'var(--surface-2)', display: 'flex', gap: '20px', flexWrap: 'wrap' }}>
            <div style={{ flex: 1, minWidth: '200px' }}>
              <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '8px' }}>
                {t('DORA klasifikace (Google State of DevOps 2023)', 'DORA Classification (Google State of DevOps 2023)')}
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '6px' }}>
                {(Object.entries(DORA_CFG) as [NonNullable<DoraLevel>, typeof DORA_CFG[NonNullable<DoraLevel>]][]).map(([level, cfg]) => (
                  <div key={level} style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <span style={{ width: '8px', height: '8px', borderRadius: '50%', background: cfg.color, flexShrink: 0 }} />
                    <span style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                      <span style={{ fontWeight: 600 }}>{cfg.label}</span>
                      {level === 'elite' && t(' — vícekrát denně, LT <1h', ' — several/day, <1h LT')}
                      {level === 'high' && t(' — týdně, LT <1d', ' — weekly, <1d LT')}
                      {level === 'medium' && t(' — měsíčně, LT <1t', ' — monthly, <1w LT')}
                      {level === 'low' && t(' — >6měs, LT >1měs', ' — >6mo, >1mo LT')}
                    </span>
                  </div>
                ))}
              </div>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', maxWidth: '320px', lineHeight: 1.6 }}>
              {t(
                'CFR = Change Failure Rate (% nasazení způsobujících incident). MTTR = Mean Time to Restore (čas od incidentu do obnovy). Plná přesnost vyžaduje propojení s PagerDuty nebo Alertmanagerem.',
                'CFR = Change Failure Rate (% deployments causing an incident). MTTR = Mean Time to Restore (incident to recovery). Full precision requires PagerDuty or Alertmanager integration.',
              )}
            </div>
          </div>
        </>
      )}
    </div>
  )
}

export default function DevOpsPage() {
  return (
    <AuthGuard permission="system:view">
      <DevOpsContent />
    </AuthGuard>
  )
}
