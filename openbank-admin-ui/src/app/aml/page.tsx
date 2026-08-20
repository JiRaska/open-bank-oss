// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState } from 'react'
import { ShieldAlert, Search, Clock, RefreshCw, AlertTriangle, User, Play, AlertOctagon } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader, StatusBadge } from '@/components/ui'
import { StatCard } from '@/components/ui/StatCard'

interface AmlCase {
  id: string;
  customerName: string;
  customerType: string;
  riskLevel: string;
  status: string;
  score: number;
  timestamp: string;
}

export default function AmlPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [scanning, setScanning] = useState(false)
  const [search, setSearch] = useState('')
  const { data, loading, unavailable, waking, reload } = useServiceResource<AmlCase[]>(
    svcUrl('aml-service', '/api/v1/aml/cases'),
    { select: (raw) => (Array.isArray(raw) ? (raw as AmlCase[]) : ((raw as { cases?: AmlCase[] }).cases ?? [])) },
  )
  const cases = data ?? []
  // The service can still be scanned when it's merely idle (a POST wakes it);
  // only a hard-down state blocks the button.
  const serviceReachable = !unavailable || unavailable.kind === 'no_data' || unavailable.kind === 'scaled_to_zero'

  const triggerScan = async () => {
    setScanning(true)
    try {
      await fetch(svcUrl('aml-service', '/api/v1/aml/scan'), { method: 'POST' })
      setTimeout(() => { reload(); setScanning(false) }, 3000)
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
        <PageHeader
          title={t('AML Monitoring', 'AML Monitoring')}
          subtitle={t('Prevence praní špinavých peněz — monitoring transakcí a správa případů', 'Anti-Money Laundering — transaction monitoring & case management')}
          icon={<ShieldAlert size={20} aria-hidden="true" />}
          actions={<div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <ServiceStatusBadge
              label="aml-service :8117"
              loading={loading}
              waking={waking}
              unavailable={unavailable}
              copy={{
                up: t('aml-service běží', 'aml-service is up'),
                idle: t('aml-service spí (scale-to-zero), probouzí se…', 'aml-service idle (scaled to zero), waking…'),
                down: t('aml-service neodpovídá', 'aml-service is not responding'),
                checking: t('Zjišťuji stav služby…', 'Checking service…'),
              }}
            />
            <button type="button" aria-label={t('Spustit AML kontrolu', 'Run AML scan')} aria-busy={scanning} onClick={triggerScan} disabled={scanning || !serviceReachable} className="btn btn-primary btn-sm" style={{ background: 'var(--accent)', color: 'white', border: 'none', padding: '6px 12px', borderRadius: '6px', fontSize: '13px', fontWeight: 600, cursor: scanning || !serviceReachable ? 'not-allowed' : 'pointer', display: 'flex', alignItems: 'center', gap: '6px', opacity: scanning || !serviceReachable ? 0.6 : 1 }}>
              <Play size={13} aria-hidden="true" style={{ animation: scanning ? 'pulse 1s infinite' : 'none' }} />
              {scanning ? t('Kontroluji…', 'Scanning…') : t('Spustit AML kontrolu', 'Run AML scan')}
            </button>
          </div>}
        />

        {escalated.length > 0 && (
          <div style={{ marginBottom: '20px', padding: '12px 16px', borderRadius: '8px',
            background: 'var(--danger-bg)', border: '1px solid var(--danger-border)',
            display: 'flex', alignItems: 'center', gap: '10px' }}>
            <AlertOctagon size={16} style={{ color: 'var(--danger)', flexShrink: 0 }} />
            <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--danger-text)' }}>
              {escalated.length}{' '}
              {t(
                escalated.length > 1 && escalated.length < 5
                  ? 'AML případy byly eskalovány na compliance officera'
                  : escalated.length >= 5
                    ? 'AML případů bylo eskalováno na compliance officera'
                    : 'AML případ byl eskalován na compliance officera',
                escalated.length === 1 ? 'AML case escalated to a compliance officer' : 'AML cases escalated to a compliance officer',
              )}
            </span>
          </div>
        )}

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Případů celkem', 'Total Cases'), value: cases.length, icon: <ShieldAlert size={16} />, color: 'var(--accent)' },
            { label: t('Vysoké riziko', 'High Risk'), value: highRisk.length, icon: <AlertTriangle size={16} />, color: 'var(--danger)' },
            { label: t('Čeká na review', 'Pending Review'), value: pending.length, icon: <Clock size={16} />, color: 'var(--warning)' },
            { label: t('Eskalováno', 'Escalated'), value: escalated.length, icon: <AlertOctagon size={16} />, color: '#dc2626' },
          ].map(k => <StatCard key={k.label} label={k.label} value={k.value} icon={k.icon} tone={k.color === 'var(--danger)' || k.color === '#dc2626' ? 'danger' : k.color === 'var(--warning)' ? 'warning' : undefined} />)}
        </div>

        <div className="card">
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '10px', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={13} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat jméno klienta, status, úroveň rizika…', 'Search customer name, status, risk level…')}
                aria-label={t('Hledat AML případy', 'Search AML cases')}
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
          </div>
          {loading ? (
            <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám případy…', 'Loading cases…')}</div>
            </div>
          ) : unavailable ? (
            // aml-service didn't answer through the BFF. Classify honestly — idle
            // (scale-to-zero, ADR-0057), not deployed, or a real outage — instead
            // of the old copy that falsely claimed the service was up.
            <DataUnavailable kind={unavailable.kind} service={t('AML-service', 'AML-service')} feature={t('AML monitoring', 'AML monitoring')} lang={language} />
          ) : filtered.length === 0 ? (
            <DataUnavailable
              kind="no_data"
              feature={t('AML případy', 'AML cases')}
              lang={language}
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
                      <StatusBadge status={c.riskLevel} />
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
                      <StatusBadge status={c.status} label={c.status.replace('_', ' ')} />
                    </td>
                    <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{c.timestamp ? new Date(c.timestamp).toLocaleString(numberLocale) : '—'}</td>
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
