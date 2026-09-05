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
import { PageHeader } from '@/components/ui/PageHeader'
import { StatCard } from '@/components/ui/StatCard'
import { StatusBadge } from '@/components/ui/StatusBadge'

interface ClearingBatch {
  id: string; batchReference: string; paymentRail: string; status: string
  itemCount: number; totalAmount: number; currency: string; createdAt: string; settledAt?: string
}

export default function ClearingPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [search, setSearch] = useState('')
  const [lastSuccessfulAt, setLastSuccessfulAt] = useState<Date | null>(null)
  const { data, loading, unavailable, waking, reload } = useServiceResource<ClearingBatch[]>(
    svcUrl('clearing-service', '/api/v1/clearing/batches'),
    { select: (raw) => {
      setLastSuccessfulAt(new Date())
      return Array.isArray(raw) ? (raw as ClearingBatch[]) : ((raw as { batches?: ClearingBatch[] }).batches ?? [])
    } },
  )
  const batches = data ?? []
  const hasSnapshot = data !== null
  const showingRetainedSnapshot = unavailable !== null && hasSnapshot

  const filtered = batches.filter(b =>
    b.batchReference?.toLowerCase().includes(search.toLowerCase()) ||
    b.paymentRail?.toLowerCase().includes(search.toLowerCase()) ||
    b.status?.toLowerCase().includes(search.toLowerCase())
  )

  const settled = batches.filter(b => b.status === 'SETTLED')
  const pending = batches.filter(b => b.status === 'PENDING' || b.status === 'PROCESSING')
  const totalVolume = batches.reduce((s, b) => s + (b.totalAmount ?? 0), 0)

  return (
    <AuthGuard permission="payment-rails:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <PageHeader
          icon={<Layers size={20} aria-hidden="true" />}
          title={t('Zúčtování & Vypořádání', 'Clearing & Settlement')}
          subtitle={t('Mezibankovní zúčtování — SEPA · SWIFT · Domestic netting', 'Interbank clearing — SEPA · SWIFT · Domestic netting')}
          actions={<div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
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
            <button type="button" onClick={reload} disabled={loading} aria-busy={loading} aria-label={t('Obnovit clearing dávky', 'Refresh clearing batches')} className="btn btn-secondary btn-sm">
              <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
            </button>
          </div>}
        />

        {showingRetainedSnapshot && <div role="status" aria-live="polite" style={{ marginBottom: 20 }}>
          <DataUnavailable kind={unavailable.kind} service={t('Clearing-service', 'Clearing-service')} feature={t('Aktualizace clearing dávek', 'Clearing batch refresh')} lang={language} dense />
          <p style={{ margin: '6px 0 0', color: 'var(--text-tertiary)', fontSize: 11 }}>
            {t('Zobrazen je poslední úspěšný snapshot', 'Showing the last successful snapshot')}
            {lastSuccessfulAt ? ` (${lastSuccessfulAt.toLocaleString(numberLocale)})` : ''}.
            {' '}{t('Stav vypořádání i objem se od té doby mohly změnit.', 'Settlement status and volume may have changed since then.')}
          </p>
        </div>}

        {loading && hasSnapshot && <p role="status" aria-live="polite" style={{ margin: '0 0 12px', color: 'var(--text-tertiary)', fontSize: 11 }}>
          {t('Aktualizuji clearing dávky; poslední snapshot zůstává dostupný.', 'Refreshing clearing batches; the last snapshot remains available.')}
        </p>}

        {hasSnapshot && <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Dávky celkem', 'Total batches'), value: batches.length, icon: <Layers size={16} aria-hidden="true" /> },
            { label: t('Vypořádáno', 'Settled'), value: settled.length, icon: <CheckCircle2 size={16} aria-hidden="true" />, tone: 'success' as const },
            { label: t('Čeká / Zpracovává', 'Pending / Processing'), value: pending.length, icon: <Clock size={16} aria-hidden="true" />, tone: 'warning' as const },
            { label: t('Objem (EUR)', 'Volume (EUR)'), value: totalVolume.toLocaleString(numberLocale, { maximumFractionDigits: 0 }), icon: <Banknote size={16} aria-hidden="true" /> },
          ].map(k => <StatCard key={k.label} label={k.label} value={k.value} icon={k.icon} tone={k.tone} />)}
        </div>}

        <div className="card">
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '10px', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={13} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat referenci, rail, status…', 'Search reference, rail, status…')}
                aria-label={t('Hledat clearing dávky', 'Search clearing batches')}
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
          </div>
          {loading && !hasSnapshot ? (
            <div role="status" aria-live="polite" style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám…', 'Loading…')}</div>
            </div>
          ) : unavailable && !hasSnapshot ? (
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
                return (
                  <tr key={b.id} style={{ borderBottom: '1px solid var(--border)' }}
                    onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                    onMouseLeave={e => (e.currentTarget.style.background = '')}>
                    <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>{b.batchReference}</td>
                    <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{b.paymentRail}</td>
                    <td style={{ padding: '12px 16px', fontSize: '13px', color: 'var(--text-primary)' }}>{b.itemCount}</td>
                    <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{(b.totalAmount ?? 0).toLocaleString(numberLocale, { minimumFractionDigits: 2 })}</td>
                    <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{b.currency}</td>
                    <td style={{ padding: '12px 16px' }}><StatusBadge status={b.status} tone={b.status === 'FAILED' ? 'danger' : b.status === 'SETTLED' ? 'success' : 'warning'} /></td>
                    <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{b.createdAt ? new Date(b.createdAt).toLocaleString(numberLocale) : '—'}</td>
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
