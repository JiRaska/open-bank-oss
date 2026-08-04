// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { MessageSquareWarning, Search, CheckCircle2, Clock, RefreshCw, AlertTriangle, Timer } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'

interface Dispute {
  id: string; referenceNumber: string; disputeType: string; status: string
  claimantAccountId: string; transactionId: string; amount: number; currency: string
  slaDeadline: string; createdAt: string
}

export default function DisputesPage() {
  const { t, language } = useLanguage()
  const [search, setSearch] = useState('')
  const { data, loading, unavailable, waking } = useServiceResource<Dispute[]>(
    svcUrl('dispute-service', '/api/v1/disputes'),
    { select: (raw) => (Array.isArray(raw) ? (raw as Dispute[]) : ((raw as { disputes?: Dispute[] }).disputes ?? [])) },
  )
  const disputes = data ?? []

  const filtered = disputes.filter(d =>
    d.referenceNumber?.toLowerCase().includes(search.toLowerCase()) ||
    d.disputeType?.toLowerCase().includes(search.toLowerCase()) ||
    d.status?.toLowerCase().includes(search.toLowerCase())
  )

  const open = disputes.filter(d => ['OPEN', 'UNDER_REVIEW', 'PENDING_EVIDENCE'].includes(d.status))
  const resolved = disputes.filter(d => ['RESOLVED', 'CLOSED', 'CHARGEBACK_ISSUED'].includes(d.status))
  const slaBreached = disputes.filter(d => d.slaDeadline && new Date(d.slaDeadline) < new Date() && !['RESOLVED', 'CLOSED'].includes(d.status))

  const statusBadgeClass = (s: string) => {
    if (['RESOLVED', 'CLOSED'].includes(s)) return 'badge badge-success'
    if (['CHARGEBACK_ISSUED'].includes(s)) return 'badge badge-warning'
    if (['REJECTED'].includes(s)) return 'badge badge-danger'
    return 'badge badge-info'
  }

  const slaStatus = (deadline: string, status: string) => {
    if (['RESOLVED', 'CLOSED'].includes(status)) return null
    if (!deadline) return null
    const now = Date.now()
    const daysLeft = Math.ceil((new Date(deadline).getTime() - now) / 86400000)
    if (daysLeft < 0) return <span style={{ fontSize: '11px', color: 'var(--danger)', fontWeight: 700 }}>{t('PORUŠENÍ SLA', 'SLA BREACH')}</span>
    if (daysLeft <= 5) return <span style={{ fontSize: '11px', color: 'var(--warning)', fontWeight: 600 }}>{daysLeft}{t('d zbývá', 'd left')}</span>
    return <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{daysLeft}{t('d zbývá', 'd left')}</span>
  }

  return (
    <AuthGuard permission="compliance:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <div className="page-header" style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
          <div>
            <h1 className="page-title">
              {t('Reklamace & Spory', 'Disputes & Complaints')}
            </h1>
            <p className="page-subtitle">
              {t('Správa sporů — chargeback · SLA 45 dní · PSD2 čl. 73', 'Dispute management — chargeback · SLA 45 days · PSD2 Art. 73')}
            </p>
          </div>
          <ServiceStatusBadge
            label="dispute-service :8135"
            loading={loading}
            waking={waking}
            unavailable={unavailable}
            copy={{
              up: t('dispute-service běží', 'dispute-service is up'),
              idle: t('dispute-service spí (scale-to-zero), probouzí se…', 'dispute-service idle (scaled to zero), waking…'),
              down: t('dispute-service neodpovídá', 'dispute-service is not responding'),
              checking: t('Zjišťuji stav služby…', 'Checking service…'),
            }}
          />
        </div>

        {slaBreached.length > 0 && (
          <div style={{ marginBottom: '20px', padding: '12px 16px', borderRadius: '8px',
            background: 'var(--danger-bg)', border: '1px solid var(--danger-border)',
            display: 'flex', alignItems: 'center', gap: '10px' }}>
            <AlertTriangle size={16} style={{ color: 'var(--danger)', flexShrink: 0 }} />
            <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--danger-text)' }}>
              {slaBreached.length} {t(slaBreached.length > 1 ? 'sporů překročilo SLA 45 dní' : 'spor překročil SLA 45 dní', slaBreached.length > 1 ? 'disputes exceeded the 45-day SLA' : 'dispute exceeded the 45-day SLA')} — {t('vyžaduje okamžité řešení', 'requires immediate action')}
            </span>
          </div>
        )}

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Spory celkem', 'Total disputes'), value: disputes.length, icon: <MessageSquareWarning size={16} />, color: 'var(--accent)' },
            { label: t('Otevřené', 'Open'), value: open.length, icon: <Clock size={16} />, color: 'var(--warning)' },
            { label: t('Vyřešené', 'Resolved'), value: resolved.length, icon: <CheckCircle2 size={16} />, color: 'var(--success)' },
            { label: t('SLA porušení', 'SLA breaches'), value: slaBreached.length, icon: <Timer size={16} />, color: 'var(--danger)' },
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
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat referenci, typ, status…', 'Search reference, type, status…')} className="input" style={{ paddingLeft: '30px', height: '32px' }} />
            </div>
          </div>
          {loading ? (
            <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} className="animate-spin" style={{ marginBottom: '8px', margin: '0 auto', display: 'block' }} /><div>{t('Načítám…', 'Loading…')}</div>
            </div>
          ) : unavailable ? (
            <DataUnavailable kind={unavailable.kind} service={t('Dispute-service', 'Dispute-service')} feature={t('Spory', 'Disputes')} lang={language} />
          ) : filtered.length === 0 ? (
            <DataUnavailable kind="no_data" feature={t('Spory', 'Disputes')} lang={language}
              detail={disputes.length === 0
                ? t('Služba běží, zatím žádné spory.', 'The service is running; no disputes yet.')
                : t('Žádné výsledky pro zadaný filtr.', 'No results for the applied filter.')} />
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table className="table">
                <thead><tr>
                  {[t('Reference', 'Reference'), t('Typ', 'Type'), t('Transakce', 'Transaction'), t('Částka', 'Amount'), t('Status', 'Status'), t('SLA', 'SLA'), t('Vytvořeno', 'Created')].map(h => (
                    <th key={h}>{h}</th>
                  ))}
                </tr></thead>
                <tbody>{filtered.map(d => (
                  <tr key={d.id}>
                    <td className="mono" style={{ fontWeight: 600 }}>{d.referenceNumber}</td>
                    <td style={{ color: 'var(--text-secondary)' }}>{d.disputeType}</td>
                    <td className="mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{d.transactionId?.slice(0, 8)}…</td>
                    <td style={{ fontWeight: 600 }}>
                      {(d.amount ?? 0).toLocaleString('cs-CZ', { minimumFractionDigits: 2 })} {d.currency}
                    </td>
                    <td>
                      <span className={statusBadgeClass(d.status)}>{d.status}</span>
                    </td>
                    <td>{slaStatus(d.slaDeadline, d.status)}</td>
                    <td style={{ color: 'var(--text-tertiary)' }}>{d.createdAt ? new Date(d.createdAt).toLocaleString('cs-CZ') : '—'}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </AuthGuard>
  )
}
