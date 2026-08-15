// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useState } from 'react'
import { CheckCircle2, XCircle, AlertTriangle, Activity, ShieldCheck, Timer } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

// ── The gate estate's own health, ADR-0255 ──────────────────────────────────────
//
// Self-fetching, unlike the page's other cards: this reads /api/devops/gate-health
// directly rather than being threaded through DevOpsContent's Promise.all, so
// adding it never risks the existing DORA/test-results/insights load path. The
// route serves a build-time snapshot (collect-gate-health.mjs, baked at deploy
// time from the GitHub Actions API — ADR-0061's boundary: no live token in this
// pod). A missing snapshot degrades to a small inline notice, never the page's
// full-takeover DataUnavailable — this is one section, not the whole page.

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
  collectedAt: string | null
  runsInspected?: number
  shardHistory?: ShardRun[]
  gates?: GateSummary[]
  estate?: Estate | null
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
  const { t } = useLanguage()
  const [data, setData] = useState<GateHealthResponse | null>(null)
  const [failed, setFailed] = useState(false)

  const load = useCallback(async () => {
    try {
      const res = await fetch('/api/devops/gate-health', { cache: 'no-store' })
      if (!res.ok) { setFailed(true); return }
      setData(await res.json())
      setFailed(false)
    } catch {
      setFailed(true)
    }
  }, [])

  useEffect(() => { load() }, [load])
  useEffect(() => {
    const id = setInterval(load, 60_000)
    return () => clearInterval(id)
  }, [load])

  const title = t('Zdraví CI kvalitativních bran', 'CI quality-gate health')
  const subtitle = t(
    'Snímek estate z gates.yaml (ADR-0254/0253) — vytvořeno při buildu z GitHub Actions API, žádný živý token v tomto podu.',
    'Snapshot of the gates.yaml estate (ADR-0254/0253) — collected at build time from the GitHub Actions API, no live token in this pod.',
  )

  if (failed) {
    return null // a route-level failure (not "no snapshot yet") — quiet, the page's own retry loop covers it
  }

  if (!data || !data.available) {
    return (
      <div style={{ marginBottom: '20px', padding: '14px 18px', borderRadius: '10px',
        border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <ShieldCheck size={14} style={{ color: 'var(--text-tertiary)' }} />
          <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{title}</span>
        </div>
        <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '6px' }}>
          {t(
            `Snímek zatím není k dispozici${data?.reason ? ` (${data.reason})` : ''} — objeví se po prvním buildu s collect-gate-health.mjs.`,
            `Snapshot not available yet${data?.reason ? ` (${data.reason})` : ''} — appears after the first build running collect-gate-health.mjs.`,
          )}
        </div>
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

  return (
    <div style={{ marginBottom: '24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
        <ShieldCheck size={16} style={{ color: 'var(--text-primary)' }} />
        <span style={{ fontSize: '14px', fontWeight: 800, color: 'var(--text-primary)' }}>{title}</span>
      </div>
      <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginBottom: '12px' }}>{subtitle}</div>

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
                  {g.lastRed?.sha.slice(0, 8)} · {g.lastRed?.createdAt ? new Date(g.lastRed.createdAt).toLocaleDateString() : '—'}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
