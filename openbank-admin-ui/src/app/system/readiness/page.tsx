// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

'use client'

import { useState, useEffect } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { ClipboardCheck, RefreshCw, Star, CheckCircle2, XCircle } from 'lucide-react'

interface ReadinessService {
  service: string
  money_path: boolean
  scores: Record<string, number>
  evidence: Record<string, string>
  gate: 'GO' | 'NO-GO'
}
interface ReadinessReport {
  generated_for: string
  dimensions: { code: string; name: string }[]
  services: ReadinessService[]
}

// 0 Absent · 1 Declared · 2 Verified · 3 Bank-grade
const LEVELS = [
  { score: 0, cs: 'Absent',     en: 'Absent',     color: '#ef4444', bg: 'rgba(239,68,68,0.12)' },
  { score: 1, cs: 'Declared',   en: 'Declared',   color: '#f59e0b', bg: 'rgba(245,158,11,0.14)' },
  { score: 2, cs: 'Verified',   en: 'Verified',   color: '#3b82f6', bg: 'rgba(59,130,246,0.14)' },
  { score: 3, cs: 'Bank-grade', en: 'Bank-grade', color: '#10b981', bg: 'rgba(16,185,129,0.16)' },
]
const lvl = (s: number) => LEVELS[Math.max(0, Math.min(3, s))]

export default function ReadinessPage() {
  const { t } = useLanguage()
  const [data, setData] = useState<ReadinessReport | null>(null)
  const [loading, setLoading] = useState(true)

  const load = () => {
    setLoading(true)
    fetch('/api/prod-readiness', { cache: 'no-store' })
      .then(r => r.json())
      .then((d: ReadinessReport) => setData(d))
      .catch(() => setData({ generated_for: '', dimensions: [], services: [] }))
      .finally(() => setLoading(false))
  }
  useEffect(() => { load() }, [])

  const services = data?.services ?? []
  const dims = data?.dimensions ?? []
  const go = services.filter(s => s.gate === 'GO').length
  const nogo = services.length - go
  const mp = services.filter(s => s.money_path)

  return (
    <div style={{ padding: '32px', maxWidth: '1280px', margin: '0 auto' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '8px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <ClipboardCheck size={28} style={{ color: 'var(--accent, #6366f1)' }} />
          <h1 style={{ fontSize: '24px', fontWeight: 800, margin: 0, color: 'var(--text-primary)' }}>
            {t('Připravenost na produkci', 'Production Readiness')}
          </h1>
        </div>
        <button onClick={load} style={{
          display: 'flex', alignItems: 'center', gap: '6px', padding: '8px 14px', cursor: 'pointer',
          background: 'var(--card-bg)', border: '1px solid var(--border)', borderRadius: '8px',
          color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600,
        }}>
          <RefreshCw size={15} /> {t('Obnovit', 'Refresh')}
        </button>
      </div>
      <p style={{ color: 'var(--text-secondary)', fontSize: '13px', margin: '0 0 24px', maxWidth: '780px' }}>
        {t(
          'Odvozená matice zralosti per služba × 9 technicko-provozních dimenzí (C1–C9). Generuje ji v CI prod-readiness-collector.py z repu + atestace s TTL — nikdy needitováno ručně. Gate: money-path musí mít vše ≥ Verified, kritické (Kód/Zálohy/Security) = Bank-grade.',
          'Derived maturity matrix per service × 9 technical/operational dimensions (C1–C9). Generated in CI by prod-readiness-collector.py from the repo + TTL attestations — never hand-edited. Gate: money-path needs all ≥ Verified, critical (Code/Backup/Security) = Bank-grade.',
        )}
      </p>

      {/* Summary cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '12px', marginBottom: '20px' }}>
        <SummaryCard label={t('Služeb', 'Services')} value={services.length} />
        <SummaryCard label="GO" value={go} color="#10b981" icon={<CheckCircle2 size={16} />} />
        <SummaryCard label="NO-GO" value={nogo} color="#ef4444" icon={<XCircle size={16} />} />
        <SummaryCard label={t('Money-path', 'Money-path')} value={`${mp.filter(s => s.gate === 'GO').length}/${mp.length}`} color="#f59e0b" icon={<Star size={16} />} />
      </div>

      {loading && <div style={{ color: 'var(--text-secondary)', padding: '40px', textAlign: 'center' }}>{t('Načítám…', 'Loading…')}</div>}

      {!loading && services.length === 0 && (
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
                      {s.money_path && <Star size={12} style={{ color: '#f59e0b', fill: '#f59e0b' }} />}
                      {s.service}
                    </span>
                  </td>
                  {dims.map(d => {
                    const sc = s.scores[d.code] ?? 0
                    const L = lvl(sc)
                    return (
                      <td key={d.code} style={tdStyle} title={`${d.name}: ${t(L.cs, L.en)} (${sc})\n${s.evidence[d.code] ?? ''}`}>
                        <span style={{
                          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                          width: '26px', height: '26px', borderRadius: '7px',
                          background: L.bg, color: L.color, fontWeight: 800, fontSize: '12px',
                        }}>{sc}</span>
                      </td>
                    )
                  })}
                  <td style={tdStyle}>
                    <span style={{
                      padding: '3px 9px', borderRadius: '6px', fontWeight: 700, fontSize: '11px',
                      background: s.gate === 'GO' ? 'rgba(16,185,129,0.16)' : 'rgba(239,68,68,0.12)',
                      color: s.gate === 'GO' ? '#10b981' : '#ef4444',
                    }}>{s.gate}</span>
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
              <span style={{ width: '18px', height: '18px', borderRadius: '5px', background: L.bg, color: L.color, fontWeight: 800, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: '11px' }}>{L.score}</span>
              {t(L.cs, L.en)}
            </span>
          ))}
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
            <Star size={12} style={{ color: '#f59e0b', fill: '#f59e0b' }} /> money-path
          </span>
          {data?.generated_for && (
            <span style={{ marginLeft: 'auto' }}>{t('Generováno', 'Generated')}: {data.generated_for}</span>
          )}
        </div>
      )}
    </div>
  )
}

function SummaryCard({ label, value, color, icon }: { label: string; value: number | string; color?: string; icon?: React.ReactNode }) {
  return (
    <div style={{ padding: '16px', border: '1px solid var(--border)', borderRadius: '10px', background: 'var(--card-bg)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: color ?? 'var(--text-secondary)', fontSize: '12px', fontWeight: 600 }}>
        {icon} {label}
      </div>
      <div style={{ fontSize: '26px', fontWeight: 800, marginTop: '6px', color: color ?? 'var(--text-primary)' }}>{value}</div>
    </div>
  )
}

const thStyle: React.CSSProperties = { padding: '10px 8px', textAlign: 'center', color: 'var(--text-primary)', fontWeight: 700, whiteSpace: 'nowrap' }
const tdStyle: React.CSSProperties = { padding: '8px', textAlign: 'center', color: 'var(--text-primary)', verticalAlign: 'middle' }
