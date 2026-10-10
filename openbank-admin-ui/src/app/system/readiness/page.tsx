// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useState, useEffect } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { ClipboardCheck, RefreshCw, Star, CheckCircle2, XCircle, CircleSlash } from 'lucide-react'
import { LoadingState, PageHeader, StatCard, StatusBadge, SWATCH_CLASS, type Tone } from '@/components/ui'

interface ReadinessService {
  service: string
  money_path: boolean
  scores: Record<string, number>
  evidence: Record<string, string>
  gate: 'GO' | 'NO-GO' | 'NOT-DEPLOYED'
}
interface ReadinessReport {
  generated_for: string
  dimensions: { code: string; name: string }[]
  services: ReadinessService[]
}

async function fetchReadinessReport(): Promise<ReadinessReport> {
  const response = await fetch('/api/prod-readiness', { cache: 'no-store' })
  if (!response.ok) throw new Error(`Production readiness HTTP ${response.status}`)
  return response.json() as Promise<ReadinessReport>
}

// 0 Absent · 1 Declared · 2 Verified · 3 Bank-grade.
//
// The maturity scale stays local — it is a domain scoring scale, not a lifecycle status, so it does
// not belong in the shared statusTone vocabulary. What DID move is the colour: each level now names
// a Tone (ADR-0208 D2) instead of carrying its own hex + rgba pair, so the palette is themeable and
// matches every other status surface in the app.
const LEVELS: { score: number; cs: string; en: string; tone: Tone }[] = [
  { score: 0, cs: 'Absent',     en: 'Absent',     tone: 'danger' },
  { score: 1, cs: 'Declared',   en: 'Declared',   tone: 'warning' },
  { score: 2, cs: 'Verified',   en: 'Verified',   tone: 'info' },
  { score: 3, cs: 'Bank-grade', en: 'Bank-grade', tone: 'success' },
]
const lvl = (s: number) => LEVELS[Math.max(0, Math.min(3, s))]

export default function ReadinessPage() {
  const { t } = useLanguage()
  const [data, setData] = useState<ReadinessReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState(false)
  const [reportViewedAt, setReportViewedAt] = useState(() => Date.now())

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(false)
    try {
      setData(await fetchReadinessReport())
      setReportViewedAt(Date.now())
    } catch {
      // A transport failure is not an empty collector result. Keep the last verified report on
      // screen and label it stale; on first load render a recoverable unavailable state instead.
      setUnavailable(true)
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => {
    let active = true
    void fetchReadinessReport()
      .then(report => { if (active) { setData(report); setReportViewedAt(Date.now()) } })
      .catch(() => { if (active) setUnavailable(true) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  const services = data?.services ?? []
  const dims = data?.dimensions ?? []
  const go = services.filter(s => s.gate === 'GO').length
  // Counted, not derived as `length - go`: NOT-DEPLOYED is a third verdict (#5760), and folding it
  // into NO-GO is exactly the conflation the collectors stopped making — "not production ready"
  // and "not in production" are different facts and need different work from different people.
  const nogo = services.filter(s => s.gate === 'NO-GO').length
  const undeployed = services.filter(s => s.gate === 'NOT-DEPLOYED').length
  const mp = services.filter(s => s.money_path)
  const reportDate = data?.generated_for && /^\d{4}-\d{2}-\d{2}$/.test(data.generated_for)
    ? Date.parse(`${data.generated_for}T23:59:59Z`) : Number.NaN
  const reportDateKnown = Number.isFinite(reportDate)
  const reportStale = reportDateKnown && reportViewedAt - reportDate > 2 * 86_400_000

  return (
    <div style={{ padding: '32px', maxWidth: '1280px', margin: '0 auto' }}>
      <PageHeader
        icon={<ClipboardCheck size={28} className="tone-text-accent" />}
        title={t('Připravenost na produkci', 'Production Readiness')}
        subtitle={t('Skóre C1–C9 pochází z repozitáře a časově omezených atestací při sestavení Admin UI. Není to živý stav nasazených služeb ani schválení produkčního provozu.', 'C1–C9 scores come from repository evidence and time-limited attestations when Admin UI is built. This is not live service health or approval for production operation.')}
        actions={
          <button
            type="button"
            onClick={load}
            disabled={loading}
            aria-busy={loading}
            aria-label={t('Obnovit připravenost na produkci', 'Refresh production readiness')}
            style={{
            display: 'flex', alignItems: 'center', gap: '6px', padding: '8px 14px', cursor: 'pointer',
            background: 'var(--card-bg)', border: '1px solid var(--border)', borderRadius: '8px',
            color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600,
          }}>
            <RefreshCw size={15} aria-hidden="true" /> {t('Obnovit', 'Refresh')}
          </button>
        }
      />

      {data && <p style={{ fontSize: '12px', margin: '0 0 16px', color: !reportDateKnown || reportStale ? 'var(--warning-text)' : 'var(--text-secondary)' }}>
        {t('Stav podkladů k', 'Evidence as of')} {data.generated_for || t('nezjištěno', 'unknown')}.{' '}
        {!reportDateKnown
          ? t('Datum podkladů není známé; před použitím reportu ověřte výstup collectoru.', 'Evidence date is unknown; verify the collector output before using this report.')
          : reportStale
          ? t('Report je starší než dva dny; obnovit stránku nestačí, je potřeba nový build a atestace.', 'This report is over two days old; refreshing this page is not enough — a new build and attestations are needed.')
          : t('Pro aktuální stav podů použijte Zdraví služeb.', 'Use Service Health for current pod status.')}
      </p>}

      {unavailable && (
        <div
          role="status"
          aria-live="polite"
          style={{
            marginBottom: '16px', padding: '14px 16px', border: '1px solid var(--warning-border)',
            borderRadius: '10px', background: 'var(--warning-bg)', color: 'var(--text-primary)',
            display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '16px',
          }}
        >
          <div>
            <div style={{ fontWeight: 700 }}>
              {data
                ? t('Obnovení se nezdařilo — zobrazuji poslední dostupný report.', 'Refresh failed — showing the last available report.')
                : t('Report připravenosti teď nelze načíst.', 'The readiness report is unavailable right now.')}
            </div>
            <div style={{ marginTop: '3px', color: 'var(--text-secondary)', fontSize: '12px' }}>
              {t('Skóre ani gate závěry nebyly nahrazeny prázdnými daty.', 'Scores and gate verdicts were not replaced with empty data.')}
            </div>
          </div>
          <button
            type="button"
            onClick={() => void load()}
            disabled={loading}
            aria-label={t('Zkusit znovu načíst report připravenosti', 'Retry loading the readiness report')}
            style={{
              flexShrink: 0, padding: '7px 12px', border: '1px solid var(--border)', borderRadius: '7px',
              background: 'var(--card-bg)', color: 'var(--text-primary)', cursor: 'pointer', fontWeight: 650,
            }}
          >
            {t('Zkusit znovu', 'Try again')}
          </button>
        </div>
      )}

      {/* Summary cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '12px', marginBottom: '20px' }}>
        <StatCard label={t('Služeb', 'Services')} value={services.length} />
        <StatCard label="GO" value={go} tone="success" icon={<CheckCircle2 size={16} />} />
        <StatCard label="NO-GO" value={nogo} tone="danger" icon={<XCircle size={16} />} />
        {undeployed > 0 && (
          <StatCard
            label={t('Nenasazeno', 'Not deployed')}
            value={undeployed}
            tone="neutral"
            icon={<CircleSlash size={16} />}
          />
        )}
        <StatCard
          label={t('Money-path', 'Money-path')}
          value={`${mp.filter(s => s.gate === 'GO').length}/${mp.length}`}
          tone="warning"
          icon={<Star size={16} />}
        />
      </div>

      {loading && (
        <LoadingState
          label={t('Načítám připravenost služeb…', 'Loading service readiness…')}
          description={t('Sestavuji důkazy napříč všemi dimenzemi produkční připravenosti.', 'Assembling evidence across every production-readiness dimension.')}
        />
      )}

      {!loading && !unavailable && services.length === 0 && (
        <div style={{ padding: '40px', textAlign: 'center', border: '1px dashed var(--border)', borderRadius: '12px', color: 'var(--text-secondary)' }}>
          {t('Žádná data — spusť prod-readiness-collector.py --all --json.', 'No data — run prod-readiness-collector.py --all --json.')}
        </div>
      )}

      {/* Matrix */}
      {!loading && services.length > 0 && (
        <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: '12px', background: 'var(--card-bg)' }}>
          <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: '12px' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                <th style={{ ...thStyle, textAlign: 'left', minWidth: '180px', position: 'sticky', left: 0, background: 'var(--card-bg)' }}>
                  {t('Služba', 'Service')}
                </th>
                {dims.map(d => (
                  <th key={d.code} style={thStyle} title={d.name}>
                    <div style={{ fontWeight: 700 }}>{d.code}</div>
                    <div style={{ fontSize: '10px', color: 'var(--text-secondary)', fontWeight: 500 }}>{d.name}</div>
                  </th>
                ))}
                <th style={thStyle}>GATE</th>
              </tr>
            </thead>
            <tbody>
              {services.map(s => (
                <tr key={s.service} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ ...tdStyle, textAlign: 'left', position: 'sticky', left: 0, background: 'var(--card-bg)', fontWeight: 600 }}>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
                      {s.money_path && <Star size={12} className="tone-text-warning" style={{ fill: 'currentColor' }} />}
                      {s.service}
                    </span>
                  </td>
                  {dims.map(d => {
                    const sc = s.scores[d.code] ?? 0
                    const L = lvl(sc)
                    return (
                      <td key={d.code} style={tdStyle} title={`${d.name}: ${t(L.cs, L.en)} (${sc})\n${s.evidence[d.code] ?? ''}`}>
                        <span className={SWATCH_CLASS[L.tone]}>{sc}</span>
                      </td>
                    )
                  })}
                  <td style={tdStyle}>
                    <StatusBadge status={s.gate} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Legend */}
      {!loading && services.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '16px', marginTop: '16px', fontSize: '12px', color: 'var(--text-secondary)' }}>
          {LEVELS.map(L => (
            <span key={L.score} style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
              <span className={`${SWATCH_CLASS[L.tone]} tone-swatch-sm`}>{L.score}</span>
              {t(L.cs, L.en)}
            </span>
          ))}
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
            <Star size={12} className="tone-text-warning" style={{ fill: 'currentColor' }} /> money-path
          </span>
          {data?.generated_for && (
            <span style={{ marginLeft: 'auto' }}>{t('Generováno', 'Generated')}: {data.generated_for}</span>
          )}
        </div>
      )}
    </div>
  )
}

const thStyle: React.CSSProperties = { padding: '10px 8px', textAlign: 'center', color: 'var(--text-primary)', fontWeight: 700, whiteSpace: 'nowrap' }
const tdStyle: React.CSSProperties = { padding: '8px', textAlign: 'center', color: 'var(--text-primary)', verticalAlign: 'middle' }
