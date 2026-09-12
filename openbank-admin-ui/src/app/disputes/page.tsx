// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { MessageSquareWarning, Search, CheckCircle2, Clock, RefreshCw, AlertTriangle, Timer } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'
import { PageHeader, StatCard, StatusBadge, type Tone } from '@/components/ui'
import {
  disputeDaysRemaining,
  isDisputeSlaBreached,
  isTerminalDispute,
  parseDisputeList,
  type DisputeRecord,
} from '@/lib/disputes/disputePortfolio'

export default function DisputesPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [search, setSearch] = useState('')
  const { data, loading, unavailable, waking, reload } = useServiceResource<DisputeRecord[]>(
    '/api/disputes',
    { select: parseDisputeList },
  )
  const disputes = data ?? []
  const hasSnapshot = data !== null
  const showingRetainedSnapshot = unavailable !== null && hasSnapshot

  const filtered = disputes.filter(d =>
    d.reference.toLowerCase().includes(search.toLowerCase()) ||
    d.disputeType.toLowerCase().includes(search.toLowerCase()) ||
    d.status.toLowerCase().includes(search.toLowerCase())
  )

  const open = disputes.filter(d => !isTerminalDispute(d.status))
  const resolved = disputes.filter(d => isTerminalDispute(d.status))
  const now = new Date()
  const slaBreached = disputes.filter(d => isDisputeSlaBreached(d, now))

  const slaStatus = (deadline: string | null, status: DisputeRecord['status']) => {
    if (isTerminalDispute(status)) return null
    if (!deadline) return null
    const daysLeft = disputeDaysRemaining(deadline, now)
    if (daysLeft < 0) return <span style={{ fontSize: '11px', color: 'var(--danger)', fontWeight: 700 }}>{t('PORUŠENÍ SLA', 'SLA BREACH')}</span>
    if (daysLeft <= 5) return <span style={{ fontSize: '11px', color: 'var(--warning)', fontWeight: 600 }}>{daysLeft}{t('d zbývá', 'd left')}</span>
    return <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{daysLeft}{t('d zbývá', 'd left')}</span>
  }

  return (
    <AuthGuard permission="compliance:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <PageHeader
          title={t('Reklamace & Spory', 'Disputes & Complaints')}
          subtitle={t('Portfolio všech stavů · lhůta každého případu · PSD2 čl. 73', 'All-status portfolio · each case’s resolution deadline · PSD2 Art. 73')}
          icon={<MessageSquareWarning size={18} aria-hidden="true" />}
          actions={<div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
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
            <button type="button" onClick={reload} disabled={loading} aria-busy={loading} aria-label={t('Obnovit seznam sporů', 'Refresh disputes')} className="btn btn-secondary btn-sm">
              <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
            </button>
          </div>}
        />

        {showingRetainedSnapshot && <div role="status" aria-live="polite" style={{ marginBottom: 20 }}>
          <DataUnavailable kind={unavailable.kind} service={t('Dispute-service', 'Dispute-service')} feature={t('Aktualizace sporů', 'Dispute refresh')} lang={language} dense />
          <p style={{ margin: '6px 0 0', color: 'var(--text-tertiary)', fontSize: 11 }}>
            {t('Zobrazen je poslední úspěšný snapshot; spory i SLA se od té doby mohly změnit.', 'Showing the last successful snapshot; disputes and SLA status may have changed since then.')}
          </p>
        </div>}

        {loading && hasSnapshot && <p role="status" aria-live="polite" style={{ margin: '0 0 12px', color: 'var(--text-tertiary)', fontSize: 11 }}>
          {t('Aktualizuji spory; poslední snapshot zůstává dostupný.', 'Refreshing disputes; the last snapshot remains available.')}
        </p>}

        {hasSnapshot && slaBreached.length > 0 && (
          <div style={{ marginBottom: '20px', padding: '12px 16px', borderRadius: '8px',
            background: 'var(--danger-bg)', border: '1px solid var(--danger-border)',
            display: 'flex', alignItems: 'center', gap: '10px' }}>
            <AlertTriangle size={16} style={{ color: 'var(--danger)', flexShrink: 0 }} />
            <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--danger-text)' }}>
              {slaBreached.length} {t(slaBreached.length > 1 ? 'sporů překročilo lhůtu řešení' : 'spor překročil lhůtu řešení', slaBreached.length > 1 ? 'disputes exceeded their resolution deadline' : 'dispute exceeded its resolution deadline')} — {t('vyžaduje okamžité řešení', 'requires immediate action')}
            </span>
          </div>
        )}

        {hasSnapshot && <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Spory celkem', 'Total disputes'), value: disputes.length, icon: <MessageSquareWarning size={16} />, tone: undefined },
            { label: t('Otevřené', 'Open'), value: open.length, icon: <Clock size={16} />, tone: 'warning' },
            { label: t('Vyřešené', 'Resolved'), value: resolved.length, icon: <CheckCircle2 size={16} />, tone: 'success' },
            { label: t('SLA porušení', 'SLA breaches'), value: slaBreached.length, icon: <Timer size={16} />, tone: 'danger' },
          ].map(k => (
            <StatCard key={k.label} label={k.label} value={k.value} icon={k.icon} tone={k.tone as Tone | undefined} />
          ))}
        </div>}

        <div className="card">
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '10px', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={13} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat referenci, typ, status…', 'Search reference, type, status…')} aria-label={t('Hledat spory', 'Search disputes')} className="input" style={{ paddingLeft: '30px', height: '32px' }} />
            </div>
          </div>
          {loading && !hasSnapshot ? (
            <div role="status" aria-live="polite" style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} aria-hidden="true" className="animate-spin" style={{ marginBottom: '8px', margin: '0 auto', display: 'block' }} /><div>{t('Načítám…', 'Loading…')}</div>
            </div>
          ) : unavailable && !hasSnapshot ? (
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
                    <td className="mono" style={{ fontWeight: 600 }}>{d.reference}</td>
                    <td style={{ color: 'var(--text-secondary)' }}>{d.disputeType}</td>
                    <td className="mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{d.transactionId?.slice(0, 8)}…</td>
                    <td style={{ fontWeight: 600 }}>
                      {(d.amount ?? 0).toLocaleString(numberLocale, { minimumFractionDigits: 2 })} {d.currency}
                    </td>
                    <td>
                      <StatusBadge status={d.status} />
                    </td>
                    <td>{slaStatus(d.resolutionDeadline, d.status)}</td>
                    <td style={{ color: 'var(--text-tertiary)' }}>{d.createdAt ? new Date(d.createdAt).toLocaleString(numberLocale) : '—'}</td>
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
