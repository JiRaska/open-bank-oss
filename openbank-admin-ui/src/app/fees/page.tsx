// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useMemo, useEffect, useCallback } from 'react'
import { Receipt, Search, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl, classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'

// Shape served by openbank-product-catalog GET /api/v1/fees — the bank-wide fee
// schedule, flattened from the per-product Fee model. The UI no longer hardcodes
// any of this; pricing is owned by the catalog service.
interface FeeScheduleItem {
  id: string
  code: string
  name: string
  type: string
  amount: number
  currency: string
  frequency: string
  description: string | null
  waivable: boolean
  waiveCondition: string | null
  productId: string
  productCode: string
  productName: string
  status: string
  updatedAt: string
}

export default function FeesPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [fees, setFees] = useState<FeeScheduleItem[]>([])
  const [loading, setLoading] = useState(true)
  // Typed unavailable reason → renders the calm <DataUnavailable> panel instead
  // of leaking a raw "HTTP 500" string (admin-ui graceful-state rule).
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState<string>('ALL')

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      // Via the BFF proxy (not the dedicated /api/product-catalog route): the proxy
      // detects KEDA scale-to-zero (ADR-0057) → a calm "idle" state instead of a
      // scary "unreachable", and relays the operator token (product-catalog is now
      // auth-gated). product-catalog is the KEDA-scaled fees system of record.
      const res = await fetch(svcUrl('product-catalog', '/api/v1/fees'), { cache: 'no-store' })
      if (!res.ok) {
        setUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const data = await res.json()
      if (!Array.isArray(data)) {
        setUnavailable({ kind: 'error' })
        return
      }
      setFees(data as FeeScheduleItem[])
    } catch {
      // Timeout / abort / network — product-catalog didn't answer.
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const filtered = useMemo(() => {
    return fees.filter(f => {
      if (typeFilter !== 'ALL' && f.type !== typeFilter) return false
      if (search) {
        const query = search.toLowerCase()
        if (
          !f.code.toLowerCase().includes(query) &&
          !f.name.toLowerCase().includes(query) &&
          !f.productCode.toLowerCase().includes(query)
        ) {
          return false
        }
      }
      return true
    })
  }, [fees, typeFilter, search])

  const uniqueTypes = useMemo(() => Array.from(new Set(fees.map(f => f.type))).sort(), [fees])
  const activeCount = fees.filter(f => f.status === 'ACTIVE').length

  return (
    <AuthGuard permission="product-catalog:view">
      <div>
        <PageHeader
          icon={<Receipt size={20} aria-hidden="true" />}
          title={t('Ceník poplatků', 'Fee Schedule')}
          subtitle={t('Ceník poplatků ze service product-catalog', 'Fee schedule served by the product-catalog service')}
          actions={<div className="flex gap-2">
            <button type="button" className="btn btn-secondary" onClick={() => void load()} disabled={loading} aria-busy={loading} aria-label={t('Obnovit ceník poplatků', 'Refresh fee schedule')}>
              <RefreshCw size={14} aria-hidden="true" style={loading ? { animation: 'spin 1s linear infinite' } : undefined} />
              {t('Obnovit', 'Refresh')}
            </button>
          </div>}
        />

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px', marginBottom: '20px' }}>
          <StatCard label={t('Celkem poplatků', 'Total Fees')} value={fees.length} icon={<Receipt size={14} aria-hidden="true" />} />
          <StatCard label={t('Aktivní (na aktivním produktu)', 'Active (on active product)')} value={activeCount} tone="success" />
          <StatCard label={t('Kategorie poplatků', 'Fee Categories')} value={uniqueTypes.length} />
        </div>

        <div style={{ display: 'flex', gap: '10px', marginBottom: '16px', flexWrap: 'wrap' }}>
          <div style={{ position: 'relative', flex: 1, minWidth: '250px', maxWidth: '320px' }}>
            <Search size={14} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
            <input
              className="input"
              style={{ paddingLeft: '32px', width: '100%' }}
              placeholder={t('Hledat kód, název, produkt…', 'Search code, name, product…')}
              aria-label={t('Hledat v ceníku poplatků', 'Search fee schedule')}
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
          </div>
          <div style={{ display: 'flex', gap: '4px', alignItems: 'center' }}>
            <span style={{ fontSize: '12px', color: 'var(--text-muted)', marginRight: '4px' }}>{t('Typ', 'Type')}:</span>
            <select className="input" aria-label={t('Typ poplatku', 'Fee type')} style={{ width: 'auto', padding: '6px 12px' }} value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)}>
              <option value="ALL">{t('Všechny', 'All')}</option>
              {uniqueTypes.map(typ => <option key={typ} value={typ}>{typ}</option>)}
            </select>
          </div>
        </div>

        {unavailable && (
          <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
            <DataUnavailable
              kind={unavailable.kind}
              service={t('Product-catalog', 'Product-catalog')}
              feature={t('Poplatky', 'Fees')}
              lang={language}
              detail={fees.length > 0
                ? t('Zobrazen je poslední úspěšně načtený ceník; údaje mohou být zastaralé.', 'The last successfully loaded fee schedule is shown; data may be stale.')
                : undefined}
              dense
            />
          </div>
        )}

        <div className="card" style={{ overflow: 'hidden' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>{t('Kód', 'Code')}</th>
                <th>{t('Název', 'Name')}</th>
                <th>{t('Produkt', 'Product')}</th>
                <th>{t('Typ', 'Type')}</th>
                <th>{t('Částka', 'Amount')}</th>
                <th>{t('Měna', 'Currency')}</th>
                <th>{t('Frekvence', 'Frequency')}</th>
                <th>{t('Status', 'Status')}</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={8} style={{ textAlign: 'center', padding: '40px', color: 'var(--text-muted)' }}>
                    {t('Načítám…', 'Loading…')}
                  </td>
                </tr>
              )}
              {!loading && !unavailable && filtered.length === 0 && (
                <tr>
                  <td colSpan={8} style={{ padding: 0 }}>
                    <DataUnavailable
                      kind="no_data"
                      feature={t('Poplatky', 'Fees')}
                      lang={language}
                      detail={t('Žádné poplatky nenalezeny.', 'No fees found.')}
                      dense
                    />
                  </td>
                </tr>
              )}
              {!loading && filtered.map(fee => (
                <tr key={fee.id}>
                  <td style={{ fontFamily: 'var(--font-mono)', fontWeight: 600, fontSize: '12px' }}>{fee.code}</td>
                  <td style={{ fontSize: '13px' }}>
                    {fee.name}
                    {fee.waivable && (
                      <span className="tag" style={{ marginLeft: '6px', color: 'var(--green)', fontSize: '10px' }} title={fee.waiveCondition ?? ''}>
                        {t('lze prominout', 'waivable')}
                      </span>
                    )}
                  </td>
                  <td style={{ fontSize: '12px', color: 'var(--text-secondary)' }} title={fee.productName}>
                    <span style={{ fontFamily: 'var(--font-mono)' }}>{fee.productCode}</span>
                  </td>
                  <td><span className="tag" style={{ color: 'var(--accent)' }}>{fee.type}</span></td>
                  <td style={{ fontFamily: 'var(--font-mono)' }}>
                    {fee.amount.toLocaleString(numberLocale, { minimumFractionDigits: 2 })}
                  </td>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{fee.currency}</td>
                  <td style={{ fontSize: '12px', color: 'var(--text-muted)' }}>{fee.frequency}</td>
                  <td><StatusBadge status={fee.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </AuthGuard>
  )
}
