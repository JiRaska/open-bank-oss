// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState } from 'react'
import Link from 'next/link'
import { Landmark, Search, Plus, Filter } from 'lucide-react'
import type { Account, CursorPage } from '@/types'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { hasIbanShape, isValidIban, looksLikeUuid, normalizeIban } from '@/lib/validation/iban'
import { PageHeader, StatusBadge } from '@/components/ui'
import { Can } from '@/components/auth/AuthGuard'
import { PartySearch, type PartyHit } from '@/components/party/PartySearch'

const ACCOUNT_SERVICE = '/api/svc/account-service'
// Cap every request and the rendered list. The operator never needs the full
// table at once; a bounded page keeps both the backend query and the DOM cheap
// (admin-ui pagination rule — see admin-ui CLAUDE.md).
const PAGE_SIZE = 25

// What the single smart query resolves to. account-service now serves three lookup
// shapes: an exact IBAN (`/iban/{iban}`), a Party-ID list (`?partyId={uuid}`), and a
// trigram **fragment** search over the account number (`/accounts/search?q=`, pg_trgm
// GIN — ADR-0055 / #268). A 1-character scrap is below the service's 2-char minimum
// fragment length, so it stays `unsupported` and degrades calmly rather than firing a
// query the service would reject.
type QueryKind = 'empty' | 'iban' | 'iban_malformed' | 'party' | 'fragment' | 'unsupported'

function classifyQuery(raw: string): QueryKind {
  const v = raw.trim()
  if (!v) return 'empty'
  if (looksLikeUuid(v)) return 'party'
  // Only treat it as an IBAN attempt when it actually has IBAN structure. That keeps a
  // stray `*` or a name out of the IBAN branch — it never gets the "Invalid IBAN"
  // treatment, it's a trigram fragment instead.
  if (hasIbanShape(v)) return isValidIban(v) ? 'iban' : 'iban_malformed'
  // Any other free text is a trigram fragment search, as long as it clears the
  // service's 2-char minimum (MIN_SEARCH_FRAGMENT); shorter is unsearchable.
  return v.length >= 2 ? 'fragment' : 'unsupported'
}

export default function AccountsPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [query, setQuery]               = useState('')
  const [selectedParty, setSelectedParty] = useState<PartyHit | null>(null)
  const [statusFilter, setStatusFilter] = useState('')
  const [typeFilter, setTypeFilter]     = useState('')
  const [result, setResult]             = useState<CursorPage<Account> | null>(null)
  const [loading, setLoading]           = useState(false)
  // Typed unavailable reason → renders the calm <DataUnavailable> panel instead
  // of a raw "HTTP 500" / "Invalid IBAN" leak (admin-ui graceful-state rule).
  // `unsupported` is a UI-only kind (no backend call was made).
  const [unavailable, setUnavailable]   = useState<{ kind: UnavailableKind | 'unsupported' } | null>(null)
  // Inline hint for a value the operator clearly meant as an IBAN but mistyped
  // (right shape, failed checksum). Kept next to the input; never a backend leak.
  const [ibanHint, setIbanHint]         = useState<string | null>(null)
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)

  const kind = classifyQuery(query)
  const canSearch = kind === 'iban' || kind === 'party' || kind === 'fragment'

  function resetFilters() {
    setQuery('')
    setSelectedParty(null)
    setStatusFilter('')
    setTypeFilter('')
    setResult(null)
    setUnavailable(null)
    setIbanHint(null)
    setVisibleCount(PAGE_SIZE)
  }

  async function search(value = query) {
    const rawQuery = value
    const k = classifyQuery(rawQuery)
    setIbanHint(null)
    setUnavailable(null)
    setVisibleCount(PAGE_SIZE)

    if (k === 'empty') return
    if (k === 'iban_malformed') {
      // A real IBAN typo (correct shape, wrong check digits) — actionable inline
      // hint, no fetch, no raw backend error.
      setResult(null)
      setIbanHint(t('Kontrolní číslice IBANu nesedí — zkontrolujte zadání.', 'IBAN check digits don’t match — please verify the value.'))
      return
    }
    if (k === 'unsupported') {
      // Below the 2-char trigram minimum (a single stray character) — there is
      // nothing to search on yet. Degrade calmly rather than firing a query the
      // service would reject.
      setResult(null)
      setUnavailable({ kind: 'unsupported' })
      return
    }

    setLoading(true)
    try {
      // Strip glob wildcards (`*`, `?`) and normalize to upper-case before sending
      // as a fragment — IBANs are stored upper-case and the backend escapes `*`
      // literally, so "CZ*" would never match without this normalisation.
      const fragment = rawQuery.trim().replace(/[*?]/g, '').toUpperCase()
      const url = k === 'iban'
        ? `${ACCOUNT_SERVICE}/api/v1/accounts/iban/${normalizeIban(rawQuery)}`
        : k === 'fragment'
          ? `${ACCOUNT_SERVICE}/api/v1/accounts/search?q=${encodeURIComponent(fragment)}&limit=${PAGE_SIZE}`
          : `${ACCOUNT_SERVICE}/api/v1/accounts?partyId=${encodeURIComponent(rawQuery.trim())}&limit=${PAGE_SIZE}`

      const res = await fetch(url, { signal: AbortSignal.timeout(8000) })
      if (!res.ok) {
        setResult(null)
        setUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const body = await res.json()
      if (k === 'iban') {
        setResult({ data: [body as Account], pagination: { limit: 1, hasNextPage: false } })
      } else if (Array.isArray(body)) {
        setResult({ data: body as Account[], pagination: { limit: body.length, hasNextPage: false } })
      } else {
        setResult(body as CursorPage<Account>)
      }
    } catch {
      // Timeout / abort / network — the BFF or account-service didn't answer.
      setResult(null)
      setUnavailable({ kind: 'unreachable' })
    } finally { setLoading(false) }
  }

  // Status/type are client-side refinements over the fetched slice.
  const filtered = result?.data.filter(a => {
    if (statusFilter && a.status !== statusFilter) return false
    if (typeFilter && a.accountType !== typeFilter) return false
    return true
  }) ?? []

  const visible = filtered.slice(0, visibleCount)
  const hasMore = filtered.length > visibleCount
  const queryHelpVisible = !ibanHint && !result && !unavailable

  return (
    <div>
      <PageHeader
        title={t('Účty zákazníků', 'Customer Accounts')}
        subtitle={t('Vyhledávejte a spravujte bankovní účty', 'Search and manage bank accounts')}
        icon={<Landmark size={18} aria-hidden="true" />}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Účty', 'Accounts')}</span></div>}
        actions={<Can permission="accounts:create"><Link href="/accounts/new" className="btn btn-primary">
          <Plus size={14} aria-hidden="true" /> {t('Založit účet', 'Open Account')}
        </Link></Can>}
      />

      <PartySearch
        selectedId={selectedParty?.id}
        busy={loading}
        onSelect={party => {
          setSelectedParty(party)
          setQuery(party.id)
          void search(party.id)
        }}
        placeholder={t('Jméno, název firmy nebo UUID party…', 'Party name, company name, or party UUID…')}
      />

      <div className="card">
        {/* Search toolbar */}
        <div style={{
          padding: '14px 16px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--surface-2)',
          borderRadius: '8px 8px 0 0',
          display: 'flex', flexDirection: 'column', gap: '8px'
        }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: '1', minWidth: '260px' }}>
              <Search size={13} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input
                id="accounts-query"
                className="input"
                style={{ paddingLeft: '30px', width: '100%', ...(ibanHint ? { borderColor: 'var(--danger)' } : {}) }}
                aria-label={t('Vyhledat účet podle čísla, IBANu nebo Party ID', 'Search accounts by number, IBAN, or Party ID')}
                aria-describedby={ibanHint ? 'accounts-query-error' : queryHelpVisible ? 'accounts-query-help' : undefined}
                aria-invalid={Boolean(ibanHint)}
                placeholder={t('Fragment čísla účtu, IBAN nebo Party ID (UUID)…', 'Account-number fragment, IBAN, or Party ID (UUID)…')}
                value={query}
                onChange={e => { setQuery(e.target.value); setSelectedParty(null); if (ibanHint) setIbanHint(null) }}
                onKeyDown={e => e.key === 'Enter' && search()}
              />
            </div>
            <select
              aria-label={t('Filtrovat podle stavu účtu', 'Filter by account status')}
              className="input"
              style={{ width: '150px' }}
              value={statusFilter}
              onChange={e => setStatusFilter(e.target.value)}
            >
              <option value="">{t('Všechny stavy', 'All statuses')}</option>
              <option value="ACTIVE">{t('Aktivní', 'Active')}</option>
              <option value="FROZEN">{t('Zmrazený', 'Frozen')}</option>
              <option value="DORMANT">{t('Spící', 'Dormant')}</option>
              <option value="CLOSED">{t('Uzavřený', 'Closed')}</option>
            </select>
            <select
              aria-label={t('Filtrovat podle typu účtu', 'Filter by account type')}
              className="input"
              style={{ width: '140px' }}
              value={typeFilter}
              onChange={e => setTypeFilter(e.target.value)}
            >
              <option value="">{t('Všechny typy', 'All types')}</option>
              <option value="CURRENT">{t('Běžný', 'Current')}</option>
              <option value="SAVINGS">{t('Spořicí', 'Savings')}</option>
              <option value="TERM_DEPOSIT">{t('Termínovaný', 'Term deposit')}</option>
            </select>
            <button
              className="btn btn-primary"
              type="button"
              aria-label={t('Vyhledat účty', 'Search accounts')}
              aria-busy={loading}
              onClick={() => void search()}
              disabled={loading || !canSearch}
            >
              <Search size={13} aria-hidden="true" />
              {loading ? t('Hledám…', 'Searching…') : t('Hledat', 'Search')}
            </button>
            <button
              className="btn btn-ghost"
              type="button"
              aria-label={t('Vymazat filtry účtů', 'Reset account filters')}
              onClick={resetFilters}
              disabled={loading}
            >
              {t('Vymazat', 'Reset')}
            </button>
            {result && (
              <span style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginLeft: 'auto' }}>
                <Filter size={11} aria-hidden="true" style={{ display: 'inline', marginRight: '4px' }} />
                {t(`${filtered.length} výsledků`, `${filtered.length} result${filtered.length !== 1 ? 's' : ''}`)}
              </span>
            )}
          </div>
          {/* Inline hints — never a raw backend error. */}
          {ibanHint && (
            <span id="accounts-query-error" role="alert" style={{ fontSize: '11px', color: 'var(--danger)' }}>{ibanHint}</span>
          )}
          {queryHelpVisible && (
            <span id="accounts-query-help" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
              {t(
                'Hledejte podle fragmentu čísla účtu (trigram, ≥2 znaky), přesného IBANu nebo Party ID (UUID). Jméno a název firmy vyřeší vyhledávání party výše.',
                'Search by an account-number fragment (trigram, ≥2 chars), an exact IBAN, or a Party ID (UUID). Use the party search above for names and companies.',
              )}
            </span>
          )}
        </div>

        {/* Too short for the trigram search (1 char, e.g. a stray `*`) → guidance, not a crash. */}
        {unavailable?.kind === 'unsupported' && (
          <DataUnavailable
            kind="no_data"
            feature={t('Vyhledávání', 'Search')}
            lang={language}
            title={t('Zadejte alespoň 2 znaky', 'Enter at least 2 characters')}
            detail={t(
              'Fragmentové vyhledávání čísla účtu potřebuje aspoň 2 znaky. Zadejte fragment čísla účtu, přesný IBAN nebo Party ID (UUID), nebo použijte vyhledávání party výše.',
              'Account-number fragment search needs at least 2 characters. Enter an account-number fragment, an exact IBAN, or a Party ID (UUID), or use the party search above.',
            )}
            dense
          />
        )}

        {/* Backend-derived unavailable state — never a raw HTTP status. */}
        {unavailable && unavailable.kind !== 'unsupported' && (
          <DataUnavailable
            kind={unavailable.kind}
            service={t('Account-service', 'Account-service')}
            feature={t('Účty', 'Accounts')}
            lang={language}
            dense
          />
        )}

        {/* Table */}
        {!unavailable && (
          <div style={{ overflowX: 'auto' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th>{t('Číslo účtu', 'Account Number')}</th>
                  <th>{t('Typ', 'Type')}</th>
                  <th>{t('Měna', 'CCY')}</th>
                  <th>{t('Stav', 'Status')}</th>
                  <th>{t('Party ID', 'Party ID')}</th>
                  <th>{t('Otevřen', 'Opened')}</th>
                  <th style={{ textAlign: 'right' }}>{t('Akce', 'Actions')}</th>
                </tr>
              </thead>
              <tbody>
                {!result && !loading && (
                  <tr><td colSpan={7}><div className="empty-state">{t('Vyberte party výše nebo zadejte IBAN či fragment čísla účtu.', 'Select a party above or enter an IBAN or account-number fragment.')}</div></td></tr>
                )}
                {loading && (
                  <tr><td colSpan={7}><div className="empty-state">{t('Hledám…', 'Searching…')}</div></td></tr>
                )}
                {!loading && result && filtered.length === 0 && (
                  <tr><td colSpan={7}>
                    <DataUnavailable
                      kind="no_data"
                      feature={t('Účty', 'Accounts')}
                      lang={language}
                      detail={t('Pro zadané parametry nebyl nalezen žádný účet.', 'No accounts were found for the given parameters.')}
                      dense
                    />
                  </td></tr>
                )}
                {!loading && visible.map(a => (
                  <tr key={a.id}>
                    <td><span className="mono" style={{ fontSize: '12px', color: 'var(--text-primary)', fontWeight: 500 }}>{a.accountNumber}</span></td>
                    <td><span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{a.accountType}</span></td>
                    <td><span className="tag">{a.currencyCode}</span></td>
                    <td><StatusBadge status={a.status} /></td>
                    <td><span className="mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{a.partyId}</span></td>
                    <td><span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{new Date(a.openedAt).toLocaleDateString(numberLocale)}</span></td>
                    <td style={{ textAlign: 'right' }}>
                      <Link href={`/accounts/${a.id}`} className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: '12px' }}>
                        {t('Detail', 'View')} →
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {!unavailable && hasMore && (
          <div style={{ padding: '12px 16px', borderTop: '1px solid var(--border)', textAlign: 'center' }}>
            <button
              className="btn btn-secondary"
              type="button"
              aria-label={t('Zobrazit další účty', 'Load more accounts')}
              style={{ fontSize: '12px' }}
              onClick={() => setVisibleCount(c => c + PAGE_SIZE)}
            >
              {t(`Zobrazit dalších ${Math.min(PAGE_SIZE, filtered.length - visibleCount)}`, `Load ${Math.min(PAGE_SIZE, filtered.length - visibleCount)} more`)}
              {' '}({visibleCount}/{filtered.length})
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
