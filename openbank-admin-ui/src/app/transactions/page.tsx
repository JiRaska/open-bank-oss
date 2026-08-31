// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState } from 'react'
import { ArrowLeftRight, Search, RefreshCw, Filter, X } from 'lucide-react'
import { svcUrl, classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader, StatusBadge } from '@/components/ui'

const TYPE_COLOR: Record<string, string> = {
  DEBIT:      'var(--danger)',
  CREDIT:     'var(--success)',
  TRANSFER:   'var(--accent)',
  FEE:        'var(--warning)',
  INTEREST:   '#7c3aed',
  REVERSAL:   'var(--text-tertiary)',
  ADJUSTMENT: 'var(--text-tertiary)',
}

interface TxResult {
  id: string
  referenceNumber: string
  type: string
  sourceAccountId?: string
  targetAccountId?: string
  amount: number
  currencyCode: string
  status: string
  description?: string
  valueDate: string
  bookingDate: string
  initiatedAt: string
  completedAt?: string
}

interface SearchResult {
  data: TxResult[]
  count: number
  limit: number
  offset: number
}

const CHANNELS = ['API', 'BRANCH', 'ATM', 'MOBILE', 'INTERNET', 'BATCH']
const STATUSES = ['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REVERSED']
const TYPES    = ['DEBIT', 'CREDIT', 'TRANSFER', 'FEE', 'INTEREST', 'REVERSAL', 'ADJUSTMENT']

export default function TransactionsPage() {
  const { t, language } = useLanguage()
  const [showFilters, setShowFilters] = useState(false)
  const [loading, setLoading] = useState(false)
  const [failure, setFailure] = useState<BffFailure | null>(null)
  const [result, setResult] = useState<SearchResult | null>(null)

  // Search fields
  const [accountId, setAccountId]         = useState('')
  const [iban, setIban]                   = useState('')
  const [bban, setBban]                   = useState('')
  const [referenceNumber, setReferenceNumber] = useState('')
  const [endToEndId, setEndToEndId]       = useState('')
  const [counterparty, setCounterparty]   = useState('')
  const [status, setStatus]               = useState('')
  const [type, setType]                   = useState('')
  const [dateFrom, setDateFrom]           = useState('')
  const [dateTo, setDateTo]               = useState('')
  const [amountMin, setAmountMin]         = useState('')
  const [amountMax, setAmountMax]         = useState('')
  const [channel, setChannel]             = useState('')

  const hasFilters = [iban, bban, referenceNumber, endToEndId, counterparty, status, type, dateFrom, dateTo, amountMin, amountMax, channel].some(Boolean)

  async function search(offset = 0) {
    setLoading(true); setFailure(null)
    try {
      const params = new URLSearchParams()
      if (accountId)     params.set('accountId', accountId)
      if (iban)          params.set('iban', iban)
      if (bban)          params.set('bban', bban)
      if (referenceNumber) params.set('referenceNumber', referenceNumber)
      if (endToEndId)    params.set('endToEndId', endToEndId)
      if (counterparty)  params.set('counterparty', counterparty)
      if (status)        params.set('status', status)
      if (type)          params.set('type', type)
      if (dateFrom)      params.set('dateFrom', dateFrom)
      if (dateTo)        params.set('dateTo', dateTo)
      if (amountMin)     params.set('amountMin', amountMin)
      if (amountMax)     params.set('amountMax', amountMax)
      if (channel)       params.set('channel', channel)
      params.set('limit', '50')
      params.set('offset', String(offset))

      const res = await fetch(svcUrl('transaction-service', '/api/v1/transactions/search', Object.fromEntries(params)))
      if (!res.ok) {
        // Degrade gracefully: a 404 here almost always means transaction-service
        // is not deployed in this environment (most of the fleet isn't in the
        // sandbox), not that the search itself failed. Distinguish the cases so
        // the operator sees a meaningful state instead of a raw "HTTP 404".
        setFailure(await classifyBffFailure(res))
        return
      }
      setResult(await res.json())
    } catch {
      // Network-level failure (BFF unreachable from the browser).
      setFailure('unreachable')
    } finally {
      setLoading(false)
    }
  }

  function clearFilters() {
    setIban(''); setBban(''); setReferenceNumber(''); setEndToEndId('')
    setCounterparty(''); setStatus(''); setType(''); setDateFrom('')
    setDateTo(''); setAmountMin(''); setAmountMax(''); setChannel('')
  }

  return (
    <div>
      <PageHeader
        title={t('Transakční deník', 'Transaction Ledger')}
        subtitle={t('BIAN vyhledávání — Account ID, IBAN, BBAN, reference, protistrana, částka, datum', 'BIAN-aligned search — Account ID, IBAN, BBAN, reference, counterparty, amount, date range')}
        icon={<ArrowLeftRight size={18} aria-hidden="true" />}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Transakce', 'Transactions')}</span></div>}
      />

      <div className="card" style={{ marginBottom: '16px' }}>
        {/* Primary search bar */}
        <div style={{ padding: '14px 16px', borderBottom: '1px solid var(--border)', background: 'var(--surface-2)', borderRadius: '8px 8px 0 0', display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
          <div style={{ position: 'relative', flex: 1, minWidth: '200px' }}>
            <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
            <label className="sr-only" htmlFor="transaction-account-id">{t('Hledat podle ID účtu', 'Search by account ID')}</label>
            <input id="transaction-account-id" className="input" style={{ paddingLeft: '30px', width: '100%' }}
              placeholder={t('ID účtu (UUID)…', 'Account ID (UUID)…')}
              value={accountId} onChange={e => setAccountId(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && search()} />
          </div>
          <div style={{ position: 'relative', flex: 1, minWidth: '180px' }}>
            <label className="sr-only" htmlFor="transaction-iban">{t('Filtrovat podle IBAN', 'Filter by IBAN')}</label>
            <input id="transaction-iban" className="input" style={{ width: '100%' }}
              placeholder={t('IBAN (CZ65 0800 …)', 'IBAN (CZ65 0800 …)')}
              value={iban} onChange={e => setIban(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && search()} />
          </div>
          <div style={{ position: 'relative', flex: 1, minWidth: '160px' }}>
            <label className="sr-only" htmlFor="transaction-bban">{t('Filtrovat podle BBAN', 'Filter by BBAN')}</label>
            <input id="transaction-bban" className="input" style={{ width: '100%' }}
              placeholder={t('BBAN (123456-1234567890/0800)', 'BBAN (123456-1234567890/0800)')}
              value={bban} onChange={e => setBban(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && search()} />
          </div>
          <button type="button" className="btn btn-secondary" onClick={() => setShowFilters(f => !f)} aria-expanded={showFilters} aria-controls="transaction-search-filters">
            <Filter size={13} />
            {hasFilters ? <span style={{ color: 'var(--accent)' }}>{t('Filtry', 'Filters')} ({[iban,bban,referenceNumber,endToEndId,counterparty,status,type,dateFrom,dateTo,amountMin,amountMax,channel].filter(Boolean).length})</span> : t('Filtry', 'Filters')}
          </button>
          <button type="button" className="btn btn-primary" onClick={() => search()} disabled={loading} aria-busy={loading} aria-label={loading ? t('Vyhledávání transakcí', 'Searching transactions') : t('Vyhledat transakce', 'Search transactions')}>
            {loading ? <RefreshCw size={13} aria-hidden="true" className="animate-spin" /> : <Search size={13} aria-hidden="true" />}
            {loading ? t('Hledám…', 'Searching…') : t('Hledat', 'Search')}
          </button>
        </div>

        {/* Extended filters */}
        {showFilters && (
          <div id="transaction-search-filters" style={{ padding: '16px', borderBottom: '1px solid var(--border)', background: 'var(--surface-2)' }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: '10px' }}>
              <div>
                <label htmlFor="transaction-reference" style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'block', marginBottom: '4px' }}>{t('Referenční číslo', 'Reference number')}</label>
                <input id="transaction-reference" className="input" style={{ width: '100%' }} placeholder={t('TXN202506…', 'TXN202506…')} value={referenceNumber} onChange={e => setReferenceNumber(e.target.value)} />
              </div>
              <div>
                <label htmlFor="transaction-e2e-id" style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'block', marginBottom: '4px' }}>{t('End-to-End ID', 'End-to-End ID')}</label>
                <input id="transaction-e2e-id" className="input" style={{ width: '100%' }} placeholder={t('E2E-ID…', 'E2E-ID…')} value={endToEndId} onChange={e => setEndToEndId(e.target.value)} />
              </div>
              <div>
                <label htmlFor="transaction-counterparty" style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'block', marginBottom: '4px' }}>{t('Protistrana (název)', 'Counterparty (name)')}</label>
                <input id="transaction-counterparty" className="input" style={{ width: '100%' }} placeholder={t('Jan Novák…', 'Jane Smith…')} value={counterparty} onChange={e => setCounterparty(e.target.value)} />
              </div>
              <div>
                <label htmlFor="transaction-status" style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'block', marginBottom: '4px' }}>{t('Status', 'Status')}</label>
                <select id="transaction-status" className="input" style={{ width: '100%' }} value={status} onChange={e => setStatus(e.target.value)}>
                  <option value="">{t('Vše', 'All')}</option>
                  {STATUSES.map(s => <option key={s} value={s}>{s}</option>)}
                </select>
              </div>
              <div>
                <label htmlFor="transaction-type" style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'block', marginBottom: '4px' }}>{t('Typ transakce', 'Transaction type')}</label>
                <select id="transaction-type" className="input" style={{ width: '100%' }} value={type} onChange={e => setType(e.target.value)}>
                  <option value="">{t('Vše', 'All')}</option>
                  {TYPES.map(ty => <option key={ty} value={ty}>{ty}</option>)}
                </select>
              </div>
              <div>
                <label htmlFor="transaction-channel" style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'block', marginBottom: '4px' }}>{t('Kanál', 'Channel')}</label>
                <select id="transaction-channel" className="input" style={{ width: '100%' }} value={channel} onChange={e => setChannel(e.target.value)}>
                  <option value="">{t('Vše', 'All')}</option>
                  {CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
                </select>
              </div>
              <div>
                <label htmlFor="transaction-date-from" style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'block', marginBottom: '4px' }}>{t('Datum od', 'Date from')}</label>
                <input id="transaction-date-from" className="input" type="date" style={{ width: '100%' }} value={dateFrom} onChange={e => setDateFrom(e.target.value)} />
              </div>
              <div>
                <label htmlFor="transaction-date-to" style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'block', marginBottom: '4px' }}>{t('Datum do', 'Date to')}</label>
                <input id="transaction-date-to" className="input" type="date" style={{ width: '100%' }} value={dateTo} onChange={e => setDateTo(e.target.value)} />
              </div>
              <div>
                <label htmlFor="transaction-amount-min" style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'block', marginBottom: '4px' }}>{t('Částka od (CZK)', 'Amount from (CZK)')}</label>
                <input id="transaction-amount-min" className="input" type="number" style={{ width: '100%' }} placeholder="0.00" value={amountMin} onChange={e => setAmountMin(e.target.value)} />
              </div>
              <div>
                <label htmlFor="transaction-amount-max" style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'block', marginBottom: '4px' }}>{t('Částka do (CZK)', 'Amount to (CZK)')}</label>
                <input id="transaction-amount-max" className="input" type="number" style={{ width: '100%' }} placeholder="999999.00" value={amountMax} onChange={e => setAmountMax(e.target.value)} />
              </div>
            </div>
            {hasFilters && (
              <button type="button" className="btn btn-secondary" style={{ marginTop: '10px' }} onClick={clearFilters} aria-label={t('Vymazat filtry transakcí', 'Clear transaction filters')}>
                <X size={12} /> {t('Vymazat filtry', 'Clear filters')}
              </button>
            )}
          </div>
        )}

        {failure && <DataUnavailable kind={failure} service="Transaction-service" feature={t('Vyhledávání transakcí', 'Transaction search')} lang={language} dense={result !== null} />}

        {/* Results */}
        {result && (
          <div>
            <div style={{ padding: '10px 16px', borderBottom: '1px solid var(--border)', fontSize: '12px', color: 'var(--text-tertiary)', display: 'flex', justifyContent: 'space-between' }}>
              <span>{t('Nalezeno:', 'Found:')} <strong style={{ color: 'var(--text-primary)' }}>{result.count}</strong> {t('transakcí', 'transactions')}</span>
              <span>{t('Zobrazeno:', 'Showing:')} {result.data.length}</span>
            </div>
            <div style={{ overflowX: 'auto' }}>
              <table className="table">
                <thead>
                  <tr>
                    <th>{t('Reference', 'Reference')}</th>
                    <th>{t('Typ', 'Type')}</th>
                    <th>{t('Částka', 'Amount')}</th>
                    <th>{t('Status', 'Status')}</th>
                    <th>{t('Zdrojový účet', 'Source account')}</th>
                    <th>{t('Cílový účet', 'Target account')}</th>
                    <th>{t('Popis', 'Description')}</th>
                    <th>{t('Datum', 'Date')}</th>
                  </tr>
                </thead>
                <tbody>
                  {result.data.length === 0 ? (
                    <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--text-tertiary)', padding: '32px' }}>{t('Žádné transakce', 'No transactions')}</td></tr>
                  ) : result.data.map(tx => (
                    <tr key={tx.id}>
                      <td style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '11px' }}>{tx.referenceNumber}</td>
                      <td><span style={{ fontSize: '11px', fontWeight: 600, color: TYPE_COLOR[tx.type] || 'var(--text-secondary)' }}>{tx.type}</span></td>
                      <td style={{ fontWeight: 600, color: tx.type === 'DEBIT' ? 'var(--danger)' : 'var(--success)' }}>
                        {tx.type === 'DEBIT' ? '-' : '+'}{Number(tx.amount).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB', { minimumFractionDigits: 2 })} {tx.currencyCode}
                      </td>
                      <td><StatusBadge status={tx.status} /></td>
                      <td style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '10px', color: 'var(--text-tertiary)' }}>{tx.sourceAccountId?.slice(0, 8)}…</td>
                      <td style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '10px', color: 'var(--text-tertiary)' }}>{tx.targetAccountId?.slice(0, 8)}…</td>
                      <td style={{ maxWidth: '200px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: '12px' }}>{tx.description || '—'}</td>
                      <td style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{tx.bookingDate}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {!result && !loading && !failure && (
          <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)' }}>
            <Search size={32} style={{ opacity: 0.3, marginBottom: '12px' }} />
            <div style={{ fontSize: '14px' }}>{t('Zadejte Account ID, IBAN, BBAN nebo jiný parametr a klikněte Hledat', 'Enter Account ID, IBAN, BBAN or another parameter and click Search')}</div>
            <div style={{ fontSize: '12px', marginTop: '6px' }}>{t('Podporuje: UUID, IBAN (CZ65 0800…), BBAN (123456-1234567890/0800), reference číslo, protistrana, datum, částka', 'Supports: UUID, IBAN (CZ65 0800…), BBAN (123456-1234567890/0800), reference number, counterparty, date, amount')}</div>
          </div>
        )}
      </div>
    </div>
  )
}
