// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect, useCallback } from 'react'
import { ShieldAlert, Search, CheckCircle2, XCircle, Clock, RefreshCw, AlertTriangle, User, Play, AlertOctagon } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'

interface AmlCase {
  id: string;
  customerName: string;
  customerType: string;
  riskLevel: string;
  status: string;
  score: number;
  timestamp: string;
}

const RISK_COLORS: Record<string, { bg: string; text: string; border: string }> = {
  CRITICAL: { bg: '#fef2f2', text: '#991b1b', border: '#fecaca' },
  HIGH:     { bg: 'var(--danger-bg)',   text: 'var(--danger-text)',   border: 'var(--danger-border)' },
  MEDIUM:   { bg: 'var(--warning-bg)',  text: 'var(--warning-text)',  border: 'var(--warning-border)' },
  LOW:      { bg: 'var(--success-bg)',  text: 'var(--success-text)',  border: 'var(--success-border)' },
  INFO:     { bg: 'var(--surface-3)',   text: 'var(--text-tertiary)', border: 'var(--border)' },
}

const STATUS_COLORS: Record<string, { bg: string; text: string; border: string }> = {
  PENDING_REVIEW: { bg: 'var(--warning-bg)',  text: 'var(--warning-text)',  border: 'var(--warning-border)' },
  ESCALATED:      { bg: 'var(--danger-bg)',   text: 'var(--danger-text)',   border: 'var(--danger-border)' },
  RESOLVED:       { bg: 'var(--success-bg)',  text: 'var(--success-text)',  border: 'var(--success-border)' },
  CLEAN:          { bg: 'var(--surface-3)',   text: 'var(--text-secondary)', border: 'var(--border)' },
}

export default function AmlPage() {
  const { t } = useLanguage()
  const [cases, setCases] = useState<AmlCase[]>([])
  const [loading, setLoading] = useState(true)
  const [scanning, setScanning] = useState(false)
  const [search, setSearch] = useState('')
  const [serviceUp, setServiceUp] = useState<boolean | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    fetch('/api/svc/aml-service/q/health/ready').then(r => setServiceUp(r.ok)).catch(() => setServiceUp(false))
    fetch('/api/svc/aml-service/api/v1/aml/cases').then(r => r.json())
      .then(d => setCases(Array.isArray(d) ? d : d.cases ?? []))
      .catch(() => setCases([]))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => { load() }, [load])

  const triggerScan = async () => {
    setScanning(true)
    try {
      await fetch('/api/svc/aml-service/api/v1/aml/scan', { method: 'POST' })
      setTimeout(() => { load(); setScanning(false) }, 3000)
    } catch { setScanning(false) }
  }

  const filtered = cases.filter(c =>
    c.customerName?.toLowerCase().includes(search.toLowerCase()) ||
    c.riskLevel?.toLowerCase().includes(search.toLowerCase()) ||
    c.status?.toLowerCase().includes(search.toLowerCase())
  )

  const highRisk = cases.filter(c => c.riskLevel === 'HIGH' || c.riskLevel === 'CRITICAL')
  const pending = cases.filter(c => c.status === 'PENDING_REVIEW')
  const escalated = cases.filter(c => c.status === 'ESCALATED')

  return (
    <AuthGuard permission="compliance:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '28px' }}>
          <div>
            <h1 style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '4px' }}>
              {t('AML Monitoring', 'AML Monitoring')}
            </h1>
            <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              {t('Prevence praní špinavých peněz — monitoring transakcí a správa případů', 'Anti-Money Laundering — transaction monitoring & case management')}
            </p>
          </div>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '11px', fontWeight: 600,
              padding: '4px 10px', borderRadius: '20px',
              background: serviceUp === true ? 'var(--success-bg)' : serviceUp === false ? 'var(--danger-bg)' : 'var(--surface-3)',
              color: serviceUp === true ? 'var(--success-text)' : serviceUp === false ? 'var(--danger-text)' : 'var(--text-tertiary)',
              border: `1px solid ${serviceUp === true ? 'var(--success-border)' : serviceUp === false ? 'var(--danger-border)' : 'var(--border)'}` }}>
              {serviceUp === true ? <CheckCircle2 size={10} /> : serviceUp === false ? <XCircle size={10} /> : <Clock size={10} />}
              aml-service :8117
            </span>
            <button onClick={triggerScan} disabled={scanning || serviceUp === false} className="btn btn-primary btn-sm" style={{ background: 'var(--accent)', color: 'white', border: 'none', padding: '6px 12px', borderRadius: '6px', fontSize: '13px', fontWeight: 600, cursor: scanning || serviceUp === false ? 'not-allowed' : 'pointer', display: 'flex', alignItems: 'center', gap: '6px', opacity: scanning || serviceUp === false ? 0.6 : 1 }}>
              <Play size={13} style={{ animation: scanning ? 'pulse 1s infinite' : 'none' }} />
              {scanning ? t('Kontroluji…', 'Scanning…') : t('Spustit AML kontrolu', 'Run AML scan')}
            </button>
          </div>
        </div>

        {escalated.length > 0 && (
          <div style={{ marginBottom: '20px', padding: '12px 16px', borderRadius: '8px',
            background: 'var(--danger-bg)', border: '1px solid var(--danger-border)',
            display: 'flex', alignItems: 'center', gap: '10px' }}>
            <AlertOctagon size={16} style={{ color: 'var(--danger)', flexShrink: 0 }} />
            <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--danger-text)' }}>
              {escalated.length} AML případ{escalated.length > 1 && escalated.length < 5 ? 'y byly eskalovány' : escalated.length >= 5 ? 'ů bylo eskalováno' : ' byl eskalován'} na compliance officera
            </span>
          </div>
        )}

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Případů celkem', 'Total Cases'), value: cases.length, icon: <ShieldAlert size={16} />, color: 'var(--accent)' },
            { label: t('Vysoké riziko', 'High Risk'), value: highRisk.length, icon: <AlertTriangle size={16} />, color: 'var(--danger)' },
            { label: t('Čeká na review', 'Pending Review'), value: pending.length, icon: <Clock size={16} />, color: 'var(--warning)' },
            { label: t('Eskalováno', 'Escalated'), value: escalated.length, icon: <AlertOctagon size={16} />, color: '#dc2626' },
          ].map(k => (
            <div key={k.label} className="stat-card">
              <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: `${k.color}18`,
                display: 'flex', alignItems: 'center', justifyContent: 'center', color: k.color, marginBottom: '10px' }}>{k.icon}</div>
              <div style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{k.value}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{k.label}</div>
            </div>
          ))}
        </div>

        <div className="card">
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '10px', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat jméno klienta, status, úroveň rizika…', 'Search customer name, status, risk level…')}
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
          </div>
          {loading ? (
            <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám případy…', 'Loading cases…')}</div>
            </div>
          ) : serviceUp === false ? (
            // aml-service is not reachable through the BFF — almost always means
            // it isn't deployed in this sandbox (most of the fleet isn't). Explain
            // it calmly instead of the old "Mikroservisa běží na portu 8117" copy,
            // which falsely claimed the service was up.
            <DataUnavailable kind="not_deployed" service="AML-service" feature={t('AML monitoring', 'AML monitoring')} />
          ) : filtered.length === 0 ? (
            <DataUnavailable
              kind="no_data"
              feature={t('AML případy', 'AML cases')}
              detail={search
                ? t('Žádný případ neodpovídá zadanému filtru. Zkuste upravit hledaný výraz.', 'No case matches the filter. Try adjusting the search term.')
                : t('Služba běží, ale zatím neeviduje žádné AML případy. Spusťte kontrolu tlačítkem „Spustit AML kontrolu".', 'Service is up but no AML cases recorded yet. Run a scan using the "Run AML scan" button.')}
            />
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                {[t('ID případu', 'Case ID'), t('Klient', 'Customer'), t('Typ', 'Type'), t('Riziko', 'Risk'), t('Skóre', 'Score'), t('Stav', 'Status'), t('Aktualizováno', 'Updated')].map(h => (
                  <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                ))}
              </tr></thead>
              <tbody>{filtered.map(c => {
                const rStyle = RISK_COLORS[c.riskLevel] || RISK_COLORS.INFO
                const sStyle = STATUS_COLORS[c.status] || STATUS_COLORS.CLEAN
                return (
                  <tr key={c.id} style={{ borderBottom: '1px solid var(--border)' }}
                    onMouseEnter={e => e.currentTarget.style.background = 'var(--surface-2)'}
                    onMouseLeave={e => e.currentTarget.style.background = ''}>
                    <td style={{ padding: '12px 16px', fontSize: '12px', fontFamily: 'var(--font-mono)', color: 'var(--text-tertiary)' }}>
                      #{c.id || '---'}
                    </td>
                    <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 500, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
                      <User size={12} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />{c.customerName}
                    </td>
                    <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.customerType}</td>
                    <td style={{ padding: '12px 16px' }}>
                      <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '10px', fontWeight: 700,
                        background: rStyle.bg, color: rStyle.text, border: `1px solid ${rStyle.border}` }}>{c.riskLevel}</span>
                    </td>
                    <td style={{ padding: '12px 16px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <div style={{ flex: 1, height: '4px', borderRadius: '2px', background: 'var(--surface-4)', overflow: 'hidden' }}>
                          <div style={{ height: '100%', width: `${c.score ?? 0}%`, borderRadius: '2px',
                            background: (c.score ?? 0) > 80 ? 'var(--danger)' : (c.score ?? 0) > 50 ? 'var(--warning)' : 'var(--success)' }} />
                        </div>
                        <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)', minWidth: '30px' }}>{c.score ?? 0}</span>
                      </div>
                    </td>
                    <td style={{ padding: '12px 16px' }}>
                      <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600,
                        background: sStyle.bg, color: sStyle.text, border: `1px solid ${sStyle.border}` }}>{c.status.replace('_', ' ')}</span>
                    </td>
                    <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{c.timestamp ? new Date(c.timestamp).toLocaleString('cs-CZ') : '—'}</td>
                  </tr>
                )
              })}</tbody>
            </table>
          )}
        </div>
      </div>
    </AuthGuard>
  )
}
