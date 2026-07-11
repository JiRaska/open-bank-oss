// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState } from 'react'
import { Layers, Search, CheckCircle2, Clock, RefreshCw, Banknote } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'

interface ClearingBatch {
  id: string; batchReference: string; paymentRail: string; status: string
  itemCount: number; totalAmount: number; currency: string; createdAt: string; settledAt?: string
}

export default function ClearingPage() {
  const { t, language } = useLanguage()
  const [search, setSearch] = useState('')
  const { data, loading, unavailable, waking } = useServiceResource<ClearingBatch[]>(
    svcUrl('clearing-service', '/api/v1/clearing/batches'),
    { select: (raw) => (Array.isArray(raw) ? (raw as ClearingBatch[]) : ((raw as { batches?: ClearingBatch[] }).batches ?? [])) },
  )
  const batches = data ?? []

  const filtered = batches.filter(b =>
    b.batchReference?.toLowerCase().includes(search.toLowerCase()) ||
    b.paymentRail?.toLowerCase().includes(search.toLowerCase()) ||
    b.status?.toLowerCase().includes(search.toLowerCase())
  )

  const settled = batches.filter(b => b.status === 'SETTLED')
  const pending = batches.filter(b => b.status === 'PENDING' || b.status === 'PROCESSING')
  const totalVolume = batches.reduce((s, b) => s + (b.totalAmount ?? 0), 0)

  const statusColor = (s: string) => {
    if (s === 'SETTLED') return { bg: 'var(--success-bg)', text: 'var(--success-text)', border: 'var(--success-border)' }
    if (s === 'FAILED') return { bg: 'var(--danger-bg)', text: 'var(--danger-text)', border: 'var(--danger-border)' }
    return { bg: 'var(--warning-bg)', text: 'var(--warning-text)', border: 'var(--warning-border)' }
  }

  return (
    <AuthGuard permission="payments:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '28px' }}>
          <div>
            <h1 style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '4px' }}>
              {t('Zúčtování & Vypořádání', 'Clearing & Settlement')}
            </h1>
            <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              {t('Mezibankovní zúčtování — SEPA · SWIFT · Domestic netting', 'Interbank clearing — SEPA · SWIFT · Domestic netting')}
            </p>
          </div>
          <ServiceStatusBadge
            label="clearing-service :8124"
            loading={loading}
            waking={waking}
            unavailable={unavailable}
            copy={{
              up: t('clearing-service běží', 'clearing-service is up'),
              idle: t('clearing-service spí (scale-to-zero), probouzí se…', 'clearing-service idle (scaled to zero), waking…'),
              down: t('clearing-service neodpovídá', 'clearing-service is not responding'),
              checking: t('Zjišťuji stav služby…', 'Checking service…'),
            }}
          />
        </div>

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Dávky celkem', 'Total batches'), value: batches.length, icon: <Layers size={16} />, color: 'var(--accent)' },
            { label: t('Vypořádáno', 'Settled'), value: settled.length, icon: <CheckCircle2 size={16} />, color: 'var(--success)' },
            { label: t('Čeká / Zpracovává', 'Pending / Processing'), value: pending.length, icon: <Clock size={16} />, color: 'var(--warning)' },
            { label: t('Objem (EUR)', 'Volume (EUR)'), value: totalVolume.toLocaleString('cs-CZ', { maximumFractionDigits: 0 }), icon: <Banknote size={16} />, color: 'var(--accent-2)' },
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
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat referenci, rail, status…', 'Search reference, rail, status…')}
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
          </div>
          {loading ? (
            <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám…', 'Loading…')}</div>
            </div>
          ) : unavailable ? (
            <DataUnavailable kind={unavailable.kind} service={t('Clearing-service', 'Clearing-service')} feature={t('Clearing dávky', 'Clearing batches')} lang={language} />
          ) : filtered.length === 0 ? (
            <DataUnavailable kind="no_data" feature={t('Clearing dávky', 'Clearing batches')} lang={language}
              detail={batches.length === 0
                ? t('Služba běží, zatím žádné clearing dávky.', 'The service is running; no clearing batches yet.')
                : t('Žádné výsledky pro zadaný filtr.', 'No results for the applied filter.')} />
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                {[t('Reference', 'Reference'), t('Rail', 'Rail'), t('Položky', 'Items'), t('Objem', 'Volume'), t('Měna', 'Currency'), t('Status', 'Status'), t('Vytvořeno', 'Created')].map(h => (
                  <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                ))}
              </tr></thead>
              <tbody>{filtered.map(b => {
                const sc = statusColor(b.status)
                return (
                  <tr key={b.id} style={{ borderBottom: '1px solid var(--border)' }}
                    onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                    onMouseLeave={e => (e.currentTarget.style.background = '')}>
                    <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>{b.batchReference}</td>
                    <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{b.paymentRail}</td>
                    <td style={{ padding: '12px 16px', fontSize: '13px', color: 'var(--text-primary)' }}>{b.itemCount}</td>
                    <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{(b.totalAmount ?? 0).toLocaleString('cs-CZ', { minimumFractionDigits: 2 })}</td>
                    <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{b.currency}</td>
                    <td style={{ padding: '12px 16px' }}>
                      <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600, background: sc.bg, color: sc.text, border: `1px solid ${sc.border}` }}>{b.status}</span>
                    </td>
                    <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{b.createdAt ? new Date(b.createdAt).toLocaleString('cs-CZ') : '—'}</td>
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
