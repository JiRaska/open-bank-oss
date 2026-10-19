// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { CheckCircle2, XCircle, AlertTriangle, Activity, ShieldCheck, Timer } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

// ── The gate estate's own health, ADR-0255 ──────────────────────────────────────
//
// Self-fetching, unlike the page's other cards: this reads /api/devops/gate-health
// directly rather than being threaded through DevOpsContent's Promise.all, so
// adding it never risks the existing DORA/test-results/insights load path. The
// The route may serve a runtime evidence snapshot or its identified bundled
// fallback. Missing or malformed evidence is shown as unknown, never hidden.

interface GateSummary {
  id: string
  group: string
  mode: string
  status: string
  lastRed: { runId: number; sha: string; createdAt: string } | null
  flaky: boolean
  selftestDeclared: boolean
  minSubjects: number | null
  budgetSeconds: number | null
  runsObserved: number
}

interface ShardRun {
  runId: number
  sha: string
  event: string
  createdAt: string
  shards: { name: string; conclusion: string | null; seconds: number | null }[]
}

interface Estate {
  total: number
  enforced: number
  advisory: number
  withSelftest: number
  withFloor: number
  withBudget: number
  flaky: number
}

interface GateHealthResponse {
  available: boolean
  reason?: string | null
  source?: string
  collectedAt: string | null
  runsInspected?: number
  gateDetailRunsInspected?: number
  shardHistory?: ShardRun[]
  gates?: GateSummary[]
  estate?: Estate | null
  evidence?: {
    state: 'ready' | 'stale' | 'unavailable' | 'bundled'
    reason?: string
    provenance?: {
      collectedAt: string
      expiresAt: string
      sourceSha: string
      runId: number
      runAttempt?: number
    }
  }
}

type RefreshProblem = 'denied' | 'unavailable' | 'malformed' | 'timeout'

function validAvailableSnapshot(value: unknown): value is GateHealthResponse {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<GateHealthResponse>
  return candidate.available === true
    && typeof candidate.collectedAt === 'string'
    && Number.isFinite(Date.parse(candidate.collectedAt))
    && Array.isArray(candidate.gates)
    && Array.isArray(candidate.shardHistory)
}

function StatCard({ icon, label, value, tone }: {
  icon: React.ReactNode; label: string; value: string; tone?: 'neutral' | 'warn' | 'bad'
}) {
  const color = tone === 'bad' ? '#dc2626' : tone === 'warn' ? '#d97706' : 'var(--text-primary)'
  return (
    <div style={{
      flex: '1 1 140px', padding: '12px 14px', borderRadius: '10px',
      border: '1px solid var(--border)', background: 'var(--surface-2)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '6px' }}>
        {icon}
        <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
          {label}
        </span>
      </div>
      <div style={{ fontSize: '20px', fontWeight: 800, color }}>{value}</div>
    </div>
  )
}

export function QualityGateHealthPanel() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [data, setData] = useState<GateHealthResponse | null>(null)
  const [refreshProblem, setRefreshProblem] = useState<RefreshProblem | null>(null)
  const [loading, setLoading] = useState(true)
  const activeRequest = useRef<AbortController | null>(null)

  const load = useCallback(async () => {
    if (activeRequest.current) return
    const controller = new AbortController()
    activeRequest.current = controller
    let timedOut = false
    const timeout = window.setTimeout(() => { timedOut = true; controller.abort() }, 10_000)
    try {
      const res = await fetch('/api/devops/gate-health', { cache: 'no-store', signal: controller.signal })
      if (!res.ok) {
        if (activeRequest.current === controller) setRefreshProblem(res.status === 401 || res.status === 403 ? 'denied' : 'unavailable')
        return
      }
      let payload: unknown
      try { payload = await res.json() } catch {
        if (activeRequest.current === controller) setRefreshProblem('malformed')
        return
      }
      if (controller.signal.aborted || activeRequest.current !== controller) return
      if (!validAvailableSnapshot(payload)) {
        const isUnavailable = payload && typeof payload === 'object'
          && ('available' in payload && payload.available === false || 'evidence' in payload
            && typeof payload.evidence === 'object' && payload.evidence !== null
            && 'state' in payload.evidence && payload.evidence.state === 'unavailable')
        setRefreshProblem(isUnavailable ? 'unavailable' : 'malformed')
        return
      }
      setData(payload)
      setRefreshProblem(null)
    } catch {
      if (activeRequest.current === controller && (!controller.signal.aborted || timedOut)) {
        setRefreshProblem(timedOut ? 'timeout' : 'unavailable')
      }
    } finally {
      window.clearTimeout(timeout)
      if (activeRequest.current === controller) {
        activeRequest.current = null
        if (!controller.signal.aborted) setLoading(false)
      }
    }
  }, [])

  useEffect(() => {
    const initial = window.setTimeout(() => { void load() }, 0)
    const interval = window.setInterval(() => { void load() }, 60_000)
    return () => {
      window.clearTimeout(initial)
      window.clearInterval(interval)
      activeRequest.current?.abort()
      activeRequest.current = null
    }
  }, [load])

  const title = t('Zdraví CI kvalitativních bran', 'CI quality-gate health')
  const subtitle = t('Evidence o kvalitativních branách a jejich bězích (ADR-0255).', 'Evidence about quality gates and their runs (ADR-0255).')
  const problemText = (problem: RefreshProblem | null) => {
    switch (problem) {
      case 'denied': return t('Přístup k důkazům je odepřen. Zkontrolujte své oprávnění a zkuste to znovu.', 'Access to this evidence was denied. Check your permission and try again.')
      case 'malformed': return t('Zdroj vrátil neplatné důkazy, které nelze ověřit.', 'The source returned evidence that could not be verified.')
      case 'timeout': return t('Načítání důkazů překročilo časový limit.', 'Evidence refresh timed out.')
      case 'unavailable': return t('Zdroj důkazů je dočasně nedostupný.', 'The evidence source is temporarily unavailable.')
      default: return null
    }
  }

  if (!data || !data.available) {
    return (
      <div style={{ marginBottom: '20px', padding: '14px 18px', borderRadius: '10px',
        border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <ShieldCheck size={14} style={{ color: 'var(--text-tertiary)' }} />
          <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{title}</span>
        </div>
        <div aria-live="polite" style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '6px' }}>
          {loading
            ? t('Načítám důkazy o kvalitativních branách…', 'Loading quality-gate evidence…')
            : t('Důkazy o kvalitativních branách nejsou dostupné. Stav bran nelze ověřit.', 'Quality-gate evidence is unavailable. Gate status cannot be verified.')}
          {problemText(refreshProblem) && <span> {problemText(refreshProblem)}</span>}
        </div>
        {!loading && <button type="button" onClick={() => { setLoading(true); void load() }} style={{ marginTop: '8px' }}>
          {t('Zkusit znovu', 'Try again')}
        </button>}
      </div>
    )
  }

  const estate = data.estate
  const gates = data.gates ?? []
  const flakyGates = gates.filter(g => g.flaky)
  const recentlyRed = gates
    .filter(g => g.lastRed)
    .sort((a, b) => (b.lastRed!.createdAt).localeCompare(a.lastRed!.createdAt))
    .slice(0, 8)
  const latestShardRun = (data.shardHistory ?? [])[0]
  const observedAt = data.evidence?.provenance?.collectedAt ?? data.collectedAt ?? ''

  return (
    <div style={{ marginBottom: '24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
        <ShieldCheck size={16} style={{ color: 'var(--text-primary)' }} />
        <span style={{ fontSize: '14px', fontWeight: 800, color: 'var(--text-primary)' }}>{title}</span>
      </div>
      <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginBottom: '12px' }}>{subtitle}</div>

      {(refreshProblem || data.evidence?.state === 'stale' || data.evidence?.state === 'unavailable') && (
        <div role="status" style={{ marginBottom: '12px', padding: '9px 12px', borderRadius: '8px',
          border: '1px solid #fcd34d', background: 'var(--surface-2)', color: 'var(--text-secondary)', fontSize: '12px' }}>
          {t('Zobrazuji poslední úspěšně načtený snímek; jeho aktuálnost nelze potvrdit.', 'Showing the last successfully loaded snapshot; its freshness cannot be confirmed.')}
          {refreshProblem && <span> {problemText(refreshProblem)}</span>}
          <button type="button" onClick={() => { setLoading(true); void load() }} style={{ marginLeft: '8px' }}>
            {t('Zkusit znovu', 'Try again')}
          </button>
        </div>
      )}

      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '12px' }}>
        {t('Poslední úspěšné pozorování', 'Last successful observation')}: <time dateTime={observedAt}>{new Date(observedAt).toLocaleString(dateLocale)}</time>
        {data.evidence?.provenance?.sourceSha && <span> · {t('zdroj', 'source')} {data.evidence.provenance.sourceSha.slice(0, 8)}</span>}
        {data.evidence?.state === 'bundled' && <span> · {t('záložní snímek', 'bundled snapshot')}</span>}
        <button type="button" disabled={loading} onClick={() => { setLoading(true); void load() }} style={{ marginLeft: '8px' }}>
          {t('Obnovit', 'Refresh')}
        </button>
      </div>

      {estate && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px', marginBottom: '14px' }}>
          <StatCard icon={<Activity size={13} />} label={t('Celkem bran', 'Total gates')} value={String(estate.total)} />
          <StatCard icon={<CheckCircle2 size={13} />} label={t('Vynucené', 'Enforced')} value={String(estate.enforced)} />
          <StatCard icon={<AlertTriangle size={13} />} label={t('Doporučující', 'Advisory')} value={String(estate.advisory)} />
          <StatCard icon={<ShieldCheck size={13} />} label={t('Se self-testem', 'With self-test')}
            value={`${estate.withSelftest}/${estate.total}`} />
          <StatCard icon={<Activity size={13} />} label={t('S podlahou subjektů', 'With subject floor')}
            value={`${estate.withFloor}/${estate.total}`} />
          <StatCard icon={<Timer size={13} />} label={t('S rozpočtem', 'With time budget')}
            value={`${estate.withBudget}/${estate.total}`} />
          <StatCard icon={<XCircle size={13} />} label={t('Nestabilní (flaky)', 'Flaky')}
            value={String(estate.flaky)} tone={estate.flaky > 0 ? 'bad' : 'neutral'} />
        </div>
      )}

      {latestShardRun && (
        <div style={{ marginBottom: '14px' }}>
          <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '6px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
            {t('Poslední běh — čas na shard', 'Latest run — wall time per shard')}
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
            {latestShardRun.shards
              .slice()
              .sort((a, b) => (b.seconds ?? 0) - (a.seconds ?? 0))
              .map(s => (
                <span key={s.name} style={{
                  fontSize: '11px', fontFamily: 'monospace', padding: '3px 8px', borderRadius: '6px',
                  border: '1px solid var(--border)',
                  background: s.conclusion === 'success' ? 'var(--surface-2)' : '#fee2e2',
                  color: s.conclusion === 'success' ? 'var(--text-secondary)' : '#dc2626',
                }}>
                  {s.name.replace('gates (', '').replace(')', '')} · {s.seconds != null ? `${Math.round(s.seconds)}s` : '—'}
                </span>
              ))}
          </div>
        </div>
      )}

      {flakyGates.length > 0 && (
        <div style={{ marginBottom: '14px', padding: '10px 14px', borderRadius: '10px',
          border: '1px solid #fca5a5', background: '#fee2e2' }}>
          <div style={{ fontSize: '12px', fontWeight: 700, color: '#991b1b', marginBottom: '4px' }}>
            {t('Nestabilní brány (PASS i FAIL na různých commitech)', 'Flaky gates (PASS and FAIL seen on distinct commits)')}
          </div>
          {flakyGates.map(g => (
            <div key={g.id} style={{ fontSize: '12px', fontFamily: 'monospace', color: '#7f1d1d' }}>{g.id}</div>
          ))}
        </div>
      )}

      {recentlyRed.length > 0 && (
        <div>
          <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '6px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
            {t('Naposledy červená (v pozorovaném okně)', 'Most recently red (within the observed window)')}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
            {recentlyRed.map(g => (
              <div key={g.id} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px' }}>
                <span style={{ fontFamily: 'monospace', color: 'var(--text-primary)' }}>{g.id}</span>
                <span style={{ color: 'var(--text-tertiary)' }}>
                  {g.lastRed?.sha.slice(0, 8)} · {g.lastRed?.createdAt ? new Date(g.lastRed.createdAt).toLocaleDateString(dateLocale) : '—'}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
