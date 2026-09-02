// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState } from 'react'
import { Repeat, Search, CheckCircle2, XCircle, Clock, RefreshCw, Calendar } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'
import { PageHeader } from '@/components/ui/PageHeader'

interface StandingOrder {
  id: string; debtorAccountId: string; creditorAccountId: string; creditorName: string
  amount: number; currency: string; frequency: string; status: string
  nextExecutionDate: string; description: string
}

const FREQ_LABELS: Record<string, string> = {
  DAILY: 'Denně', WEEKLY: 'Týdně', MONTHLY: 'Měsíčně', QUARTERLY: 'Čtvrtletně', YEARLY: 'Ročně'
}

export default function StandingOrdersPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [search, setSearch] = useState('')
  // standing-order-service is on the FinOps off-hours scaledown allowlist, so it
  // legitimately sits at zero replicas overnight/weekends. Route through the BFF
  // proxy + useServiceResource so a scaled-to-zero backend surfaces a calm
  // "idle, waking…" state (KEDA/scaledown) instead of a misleading error — and
  // auto-retries while the pod wakes. (Was a raw /q/health/ready probe that read
  // a scaled-down service as "down" and always claimed "running on port 8121".)
  const { data, loading, unavailable, waking, reload } = useServiceResource<StandingOrder[]>(
    svcUrl('standing-order-service', '/api/v1/standing-orders'),
    { select: (raw) => (Array.isArray(raw) ? (raw as StandingOrder[]) : ((raw as { standingOrders?: StandingOrder[] }).standingOrders ?? [])) },
  )
  const orders = data ?? []

  const filtered = orders.filter(o =>
    o.creditorName?.toLowerCase().includes(search.toLowerCase()) ||
    o.description?.toLowerCase().includes(search.toLowerCase()) ||
    o.currency?.includes(search.toUpperCase())
  )

  return (
    <AuthGuard>
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <PageHeader icon={<Repeat size={20} aria-hidden="true" />} title={t('Trvalé příkazy', 'Standing Orders')} subtitle={t('Trvalé příkazy a opakované platby — SEPA SCT', 'Standing orders and recurring payments — SEPA SCT')} actions={<div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <ServiceStatusBadge
              label="standing-order :8121"
              loading={loading}
              waking={waking}
              unavailable={unavailable}
              copy={{
                up: t('standing-order-service běží', 'standing-order-service is up'),
                idle: t('standing-order spí (scale-to-zero), probouzí se…', 'standing-order idle (scaled to zero), waking…'),
                down: t('standing-order-service neodpovídá', 'standing-order-service is not responding'),
                checking: t('Zjišťuji stav služby…', 'Checking service…'),
              }}
            />
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={reload}
              disabled={loading || waking}
              aria-busy={loading || waking}
              aria-label={t('Obnovit trvalé příkazy', 'Refresh standing orders')}
            >
              <RefreshCw size={13} aria-hidden="true" className={loading || waking ? 'animate-spin' : ''} />
              {t('Obnovit', 'Refresh')}
            </button>
            <span role="status" style={{ padding: '5px 9px', borderRadius: '6px', border: '1px solid var(--warning-border)', background: 'var(--warning-bg)', color: 'var(--warning-text)', fontSize: '11px', fontWeight: 600 }}>
              {t('Založení příkazu není připojeno', 'Order creation is not connected')}
            </span>
          </div>} />

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Celkem příkazů', 'Total orders'), value: orders.length, icon: <Repeat size={16} aria-hidden="true" />, color: 'var(--accent)' },
            { label: t('Aktivní', 'Active'), value: orders.filter(o => o.status === 'ACTIVE').length, icon: <CheckCircle2 size={16} aria-hidden="true" />, color: 'var(--success)' },
            { label: t('Pozastavené', 'Suspended'), value: orders.filter(o => o.status === 'SUSPENDED').length, icon: <Clock size={16} aria-hidden="true" />, color: 'var(--warning)' },
            { label: t('Zrušené', 'Cancelled'), value: orders.filter(o => o.status === 'CANCELLED').length, icon: <XCircle size={16} aria-hidden="true" />, color: 'var(--danger)' },
          ].map(k => (
            <div key={k.label} className="stat-card">
              <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: `${k.color}18`,
                display: 'flex', alignItems: 'center', justifyContent: 'center', color: k.color, marginBottom: '10px' }}>{k.icon}</div>
              <div style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{loading && orders.length === 0 ? '—' : k.value}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{k.label}</div>
            </div>
          ))}
        </div>

        <div className="card">
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '10px', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={13} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat příkazy…', 'Search orders…')}
                aria-label={t('Hledat trvalé příkazy', 'Search standing orders')}
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
          </div>
          {unavailable && <DataUnavailable kind={unavailable.kind} service={t('Standing-order-service', 'Standing-order-service')} feature={t('Trvalé příkazy', 'Standing orders')} lang={language} dense={orders.length > 0} />}
          {loading && orders.length === 0 ? (
            <div role="status" aria-live="polite" style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám…', 'Loading…')}</div>
            </div>
          ) : unavailable && orders.length === 0 ? null : filtered.length === 0 ? (
            <DataUnavailable kind="no_data" feature={t('Trvalé příkazy', 'Standing orders')} lang={language}
              detail={orders.length === 0
                ? t('Služba běží, zatím žádné trvalé příkazy.', 'The service is running; no standing orders yet.')
                : t('Žádné výsledky pro zadaný filtr.', 'No results for the applied filter.')} />
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                {[t('Příjemce', 'Recipient'), t('Částka', 'Amount'), t('Frekvence', 'Frequency'), t('Příští platba', 'Next run'), t('Status', 'Status'), t('Popis', 'Description')].map(h => (
                  <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                ))}
              </tr></thead>
              <tbody>{filtered.map(o => (
                <tr key={o.id} style={{ borderBottom: '1px solid var(--border)' }}
                  onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                  onMouseLeave={e => (e.currentTarget.style.background = '')}>
                  <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 500, color: 'var(--text-primary)' }}>{o.creditorName}</td>
                  <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '13px', color: 'var(--text-primary)' }}>
                    {o.amount?.toLocaleString(numberLocale, { minimumFractionDigits: 2 })} {o.currency}
                  </td>
                  <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{t(FREQ_LABELS[o.frequency] ?? o.frequency, o.frequency)}</td>
                  <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '5px' }}>
                    <Calendar size={11} aria-hidden="true" style={{ color: 'var(--text-tertiary)' }} />
                    {o.nextExecutionDate ? new Date(o.nextExecutionDate).toLocaleDateString(numberLocale) : '—'}
                  </td>
                  <td style={{ padding: '12px 16px' }}>
                    <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600,
                      background: o.status === 'ACTIVE' ? 'var(--success-bg)' : o.status === 'SUSPENDED' ? 'var(--warning-bg)' : 'var(--surface-3)',
                      color: o.status === 'ACTIVE' ? 'var(--success-text)' : o.status === 'SUSPENDED' ? 'var(--warning-text)' : 'var(--text-tertiary)',
                      border: `1px solid ${o.status === 'ACTIVE' ? 'var(--success-border)' : o.status === 'SUSPENDED' ? 'var(--warning-border)' : 'var(--border)'}` }}>{o.status}</span>
                  </td>
                  <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)', maxWidth: '200px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{o.description}</td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </div>
      </div>
    </AuthGuard>
  )
}
