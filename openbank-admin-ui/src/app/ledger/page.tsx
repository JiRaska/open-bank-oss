// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Fragment, useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { Search, ChevronDown, ChevronRight } from 'lucide-react'
import { svcUrl, classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { AuthGuard } from '@/components/auth/AuthGuard'
import type { JournalEntry, CursorPage } from '@/types'
import { PageHeader } from '@/components/ui/PageHeader'

const STATUS_PILL: Record<string, string> = {
  POSTED:   'pill pill-success',
  PENDING:  'pill pill-warning',
  REVERSED: 'pill pill-neutral',
  DRAFT:    'pill pill-info',
}

export default function LedgerPage() {
  const [fromDate, setFromDate] = useState(() => { const d = new Date(); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 10) })
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [toDate, setToDate]     = useState(() => new Date().toISOString().slice(0, 10))
  const [result, setResult]     = useState<CursorPage<JournalEntry> | null>(null)
  const [loading, setLoading]   = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  // Typed unavailable reason → renders the calm <DataUnavailable> panel instead
  // of a raw "HTTP 500" leak (admin-ui graceful-state rule).
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [moreError, setMoreError] = useState<string | null>(null)

  async function loadPage(cursor?: string) {
    try {
      const res = await fetch(svcUrl('ledger-service', '/api/v1/journals', { fromDate, toDate, limit: '20', ...(cursor ? { cursor } : {}) }), {
        signal: AbortSignal.timeout(8000),
      })
      if (!res.ok) {
        throw new Error(await classifyBffFailure(res))
      }
      return await res.json() as CursorPage<JournalEntry>
    } catch (error) {
      throw error instanceof Error ? error : new Error('unreachable')
    }
  }

  async function search() {
    setLoading(true); setUnavailable(null); setMoreError(null)
    try {
      const next = await loadPage()
      setResult(next)
      setExpanded(null)
    } catch (error) {
      // Timeout / abort / network — the BFF or ledger-service didn't answer.
      setUnavailable({ kind: (error as Error).message as UnavailableKind || 'unreachable' })
    } finally { setLoading(false) }
  }

  async function loadMore() {
    const cursor = result?.pagination.nextCursor
    if (!cursor) return
    setLoadingMore(true); setMoreError(null)
    try {
      const next = await loadPage(cursor)
      setResult(previous => previous ? {
        data: [...previous.data, ...next.data],
        pagination: next.pagination,
      } : next)
    } catch {
      // Keep already-read ledger records visible; a failed next page must not erase evidence.
      setMoreError(t('Další stránku se nepodařilo načíst. Zkuste to znovu.', 'The next page could not be loaded. Try again.'))
    } finally { setLoadingMore(false) }
  }

  return (
    <AuthGuard permission="accounts:view">
    <div>
      <PageHeader breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Hlavní kniha', 'General Ledger')}</span></div>} title={t('Hlavní kniha', 'General Ledger')} subtitle={t('Zápisy v podvojném účetnictví', 'Double-entry journal entries')} />

      <div className="card">
        <div style={{
          padding: '14px 16px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--surface-2)',
          borderRadius: '8px 8px 0 0',
          display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap',
        }}>
          <label htmlFor="ledger-from-date" style={{ fontSize: '12px', fontWeight: 500, color: 'var(--text-secondary)' }}>{t('Od', 'From')}</label>
          <input id="ledger-from-date" type="date" className="input" style={{ width: '150px' }} value={fromDate} onChange={e => setFromDate(e.target.value)} />
          <label htmlFor="ledger-to-date" style={{ fontSize: '12px', fontWeight: 500, color: 'var(--text-secondary)' }}>{t('Do', 'To')}</label>
          <input id="ledger-to-date" type="date" className="input" style={{ width: '150px' }} value={toDate} onChange={e => setToDate(e.target.value)} />
          <button type="button" className="btn btn-primary" onClick={search} disabled={loading} aria-busy={loading}>
            <Search size={13} aria-hidden="true" />
            {loading ? t('Načítání…', 'Loading…') : t('Načíst záznamy', 'Load Entries')}
          </button>
          {result && (
            <span style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginLeft: '4px' }}>
              {result.data.length} {t('položek', 'items')}
            </span>
          )}
        </div>

        {/* Calm, explained unavailable state — never a raw HTTP status. */}
        {unavailable && !result && (
          <DataUnavailable
            kind={unavailable.kind}
            service={t('Ledger-service', 'Ledger-service')}
            feature={t('Hlavní kniha', 'General Ledger')}
            lang={language}
            dense
          />
        )}

        {unavailable && result && <div role="status" aria-live="polite">
          <DataUnavailable
            kind={unavailable.kind}
            service={t('Ledger-service', 'Ledger-service')}
            feature={t('Aktualizace hlavní knihy', 'General Ledger refresh')}
            lang={language}
            dense
          />
          <p style={{ margin: '6px 16px 10px', color: 'var(--text-tertiary)', fontSize: 11 }}>
            {t(
              'Zobrazen je poslední úspěšný výsledek pro vybrané období. Novější účetní zápisy zatím nemusí být zahrnuté.',
              'Showing the last successful result for the selected period. Newer journal entries may not be included yet.',
            )}
          </p>
        </div>}

        {loading && result && <p role="status" aria-live="polite" style={{ margin: '10px 16px 0', color: 'var(--text-tertiary)', fontSize: 11 }}>
          {t('Aktualizuji hlavní knihu; poslední výsledek zůstává dostupný.', 'Refreshing the General Ledger; the last result remains available.')}
        </p>}

        {(!unavailable || result) && (
        <div style={{ overflowX: 'auto' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th style={{ width: '36px' }}></th>
                <th>{t('Záznam #', 'Entry #')}</th>
                <th>{t('ID Transakce', 'Transaction ID')}</th>
                <th>{t('Datum záznamu', 'Entry Date')}</th>
                <th>{t('Datum valuty', 'Value Date')}</th>
                <th>{t('Stav', 'Status')}</th>
                <th>{t('Řádky', 'Lines')}</th>
                <th>{t('Popis', 'Description')}</th>
              </tr>
            </thead>
            <tbody>
              {!result && !loading && (
                <tr><td colSpan={8}><div className="empty-state">{t('Vyberte období a klikněte na "Načíst záznamy".', 'Select a date range and click "Load Entries".')}</div></td></tr>
              )}
              {loading && !result && (
                <tr><td colSpan={8}><div className="empty-state">{t('Načítání záznamů hlavní knihy…', 'Loading journal entries…')}</div></td></tr>
              )}
              {!loading && result && result.data.length === 0 && (
                <tr><td colSpan={8}><div className="empty-state">{t('Pro toto období nebyly nalezeny žádné záznamy.', 'No journal entries found for this period.')}</div></td></tr>
              )}
              {result?.data.map(entry => {
                const isOpen = expanded === entry.id
                return (
                  <Fragment key={entry.id}>
                    <tr>
                      <td style={{ color: 'var(--text-tertiary)', paddingLeft: '14px' }}>
                        <button type="button" className="btn btn-ghost" style={{ padding: '3px' }} aria-label={isOpen ? t('Skrýt řádky deníku', 'Hide journal lines') : t('Zobrazit řádky deníku', 'Show journal lines')} aria-expanded={isOpen} aria-controls={`ledger-entry-${entry.id}`} onClick={() => setExpanded(isOpen ? null : entry.id)}>
                          {isOpen ? <ChevronDown size={13} aria-hidden="true" /> : <ChevronRight size={13} aria-hidden="true" />}
                        </button>
                      </td>
                      <td><span className="mono" style={{ fontSize: '12px', fontWeight: 500 }}>{entry.entryNumber ?? '—'}</span></td>
                      <td><span className="mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{entry.transactionId.slice(0, 8)}…</span></td>
                      <td><span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{entry.entryDate}</span></td>
                      <td><span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{entry.valueDate}</span></td>
                      <td><span className={STATUS_PILL[entry.status] ?? 'pill pill-neutral'}>{entry.status}</span></td>
                      <td>
                        <span style={{
                          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                          width: '20px', height: '20px', borderRadius: '4px',
                          background: 'var(--surface-3)', border: '1px solid var(--border)',
                          fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)',
                        }}>
                          {entry.lines.length}
                        </span>
                      </td>
                      <td style={{ maxWidth: '200px' }}>
                        <span style={{ fontSize: '12px', color: 'var(--text-secondary)', display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {entry.description ?? <span style={{ color: 'var(--text-tertiary)' }}>—</span>}
                        </span>
                      </td>
                    </tr>
                    {isOpen && (
                      <tr>
                        <td colSpan={8} style={{ padding: 0, background: 'var(--surface-2)' }}>
                          <div id={`ledger-entry-${entry.id}`} style={{ padding: '12px 20px 12px 50px', borderBottom: '2px solid var(--accent-border)' }}>
                            <div style={{ fontSize: '11px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)', marginBottom: '8px' }}>
                              {t('Řádky deníku', 'Journal Lines')}
                            </div>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
                              <thead>
                                <tr style={{ color: 'var(--text-tertiary)' }}>
                                  <th style={{ textAlign: 'left', padding: '4px 12px 4px 0', fontWeight: 600 }}>{t('Účet HK', 'GL Account')}</th>
                                  <th style={{ textAlign: 'left', padding: '4px 12px 4px 0', fontWeight: 600 }}>{t('Strana', 'Side')}</th>
                                  <th style={{ textAlign: 'right', padding: '4px 12px 4px 0', fontWeight: 600 }}>{t('Částka', 'Amount')}</th>
                                  <th style={{ textAlign: 'left', padding: '4px 12px 4px 0', fontWeight: 600 }}>CCY</th>
                                  <th style={{ textAlign: 'right', padding: '4px 12px 4px 0', fontWeight: 600 }}>{t('Základní částka', 'Base Amount')}</th>
                                  <th style={{ textAlign: 'left', padding: '4px 0', fontWeight: 600 }}>{t('Základní měna', 'Base CCY')}</th>
                                </tr>
                              </thead>
                              <tbody>
                                {entry.lines.map(line => (
                                  <tr key={line.id} style={{ borderTop: '1px solid var(--border)' }}>
                                    <td style={{ padding: '6px 12px 6px 0' }}>
                                      <span className="mono" style={{ fontSize: '11px' }}>{line.glAccountId.slice(0, 8)}…</span>
                                    </td>
                                    <td style={{ padding: '6px 12px 6px 0' }}>
                                      <span style={{
                                        fontWeight: 700, fontSize: '11px',
                                        color: line.side === 'DEBIT' ? 'var(--danger)' : 'var(--success)',
                                      }}>
                                        {line.side}
                                      </span>
                                    </td>
                                    <td style={{ padding: '6px 12px 6px 0', textAlign: 'right' }}>
                                      <span className="mono">{Number(line.amount).toLocaleString(numberLocale, { minimumFractionDigits: 2 })}</span>
                                    </td>
                                    <td style={{ padding: '6px 12px 6px 0' }}><span className="tag">{line.currencyCode}</span></td>
                                    <td style={{ padding: '6px 12px 6px 0', textAlign: 'right' }}>
                                      <span className="mono">{Number(line.baseAmount).toLocaleString(numberLocale, { minimumFractionDigits: 2 })}</span>
                                    </td>
                                    <td style={{ padding: '6px 0' }}><span className="tag">{line.baseCurrencyCode}</span></td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
        )}

        {result?.pagination.hasNextPage && (
          <div style={{ padding: '12px 16px', borderTop: '1px solid var(--border)', textAlign: 'center' }}>
            {moreError && <p role="alert" style={{ margin: '0 0 8px', fontSize: '12px', color: 'var(--danger-text)' }}>{moreError}</p>}
            <button type="button" className="btn btn-secondary" style={{ fontSize: '12px' }} onClick={loadMore} disabled={loadingMore} aria-busy={loadingMore}>
              {loadingMore ? t('Načítám…', 'Loading…') : t('Načíst další', 'Load more')}
            </button>
          </div>
        )}
      </div>
    </div>
    </AuthGuard>
  )
}
