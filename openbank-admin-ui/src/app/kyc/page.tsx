// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { ShieldCheck, Search, RefreshCw, ChevronRight } from 'lucide-react'
import Link from 'next/link'
import { PageHeader, StatusBadge } from '@/components/ui'
import { Can } from '@/components/auth/AuthGuard'
import { PartySearch, type PartyHit } from '@/components/party/PartySearch'

const KYC_SERVICE = '/api/svc/kyc-service'

const PAGE_SIZE = 20

interface KycCase {
  id: string; partyId: string; status: string
  checks: { checkType: string; status: string }[]
  reviewedBy?: string; createdAt: string; updatedAt: string
}

/**
 * `KycCasePage` as kyc-service publishes it (openapi.yaml 1.8.0, #8164) — `required: [items,
 * total, page, size, statusFilter]`. The page envelope has always been what `GET /api/v1/kyc/cases`
 * serves; only the document was wrong, and until #8163 nothing on either side replayed the contract.
 * The provider half is now pinned by `KycCasePageApiContractTest`; this is the consumer half.
 */
interface KycCasePage {
  items: KycCase[]
  total: number
  page: number
  size: number
  statusFilter: string | null
}

/**
 * Accept the envelope only when it is actually one. The page used to take
 * `Array.isArray(data) ? data : data.items ?? [data]`, which renders *something* for any JSON at
 * all — a shape drift became an empty table rather than a visible failure, which is the same
 * silence that let the spec and the implementation disagree in the first place.
 */
function isKycCasePage(value: unknown): value is KycCasePage {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<KycCasePage>
  return Array.isArray(candidate.items)
    && typeof candidate.total === 'number' && Number.isInteger(candidate.total) && candidate.total >= 0
    && typeof candidate.page === 'number' && Number.isInteger(candidate.page) && candidate.page >= 0
    && typeof candidate.size === 'number' && Number.isInteger(candidate.size) && candidate.size > 0
    && candidate.items.length <= candidate.size
    && candidate.items.every(item => Boolean(item) && typeof item === 'object' && typeof item.id === 'string')
}

export default function KycPage() {
  const [cases, setCases]     = useState<KycCase[]>([])
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [loading, setLoading] = useState(true)
  // Typed unavailable reason → renders the calm <DataUnavailable> panel instead
  // of leaking a raw "HTTP 404" string (admin-ui graceful-state rule).
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [search, setSearch]   = useState('')
  const [partyId, setPartyId] = useState('')
  const [partyIdInput, setPartyIdInput] = useState('')
  const [loadedPartyId, setLoadedPartyId] = useState<string | null>(null)
  const loadedPartyIdRef = useRef<string | null>(null)
  const [selectedParty, setSelectedParty] = useState<PartyHit | null>(null)
  const [page, setPage] = useState(0)
  const [pagination, setPagination] = useState<{ total: number; page: number; size: number } | null>(null)

  const load = useCallback(async (requestedPartyId = partyId, requestedPage = page) => {
    const scope = requestedPartyId || null
    // Two different contracts behind one page. The party-scoped route answers a single
    // KycCaseResponse or 404; the collection route answers a paginated KycCasePage, always.
    const partyScoped = Boolean(requestedPartyId)
    setLoading(true); setUnavailable(null)
    try {
      const url = partyScoped
        ? `${KYC_SERVICE}/api/v1/kyc/cases/party/${requestedPartyId}`
        : `${KYC_SERVICE}/api/v1/kyc/cases?page=${requestedPage}&size=${PAGE_SIZE}`
      const res = await fetch(url, { signal: AbortSignal.timeout(5000) })
      if (!res.ok) {
        const kind = await classifyBffFailure(res)
        // "No case for this party" is a statement only the PARTY-scoped route can make: 404 is
        // its documented answer to a lookup that misses. The collection route is declared to
        // answer 200 with a KycCasePage on every call, an empty page included — so a 404 there
        // is a route that is not served, and a 405 (on either route) is a path served for some
        // other method. Both used to render as the calm "No KYC cases found" panel, which is an
        // outage reported to the operator as a fact about the data.
        const unavailableKind = partyScoped && kind === 'not_found' ? 'no_data' : kind
        if (unavailableKind === 'no_data' || loadedPartyIdRef.current !== scope) {
          setCases([])
          setPagination(null)
          loadedPartyIdRef.current = unavailableKind === 'no_data' ? scope : null
          setLoadedPartyId(unavailableKind === 'no_data' ? scope : null)
        }
        setUnavailable({ kind: unavailableKind })
        return
      }
      const data: unknown = await res.json()
      if (partyScoped) {
        setCases(data ? [data as KycCase] : [])
        setPagination(null)
      } else {
        if (!isKycCasePage(data) || data.page !== requestedPage || data.size !== PAGE_SIZE) {
          // The envelope is not the one the spec publishes, or it is not the window we asked
          // for. Rendering it anyway would mean paging controls computed from numbers that
          // describe a different page.
          setCases([])
          setPagination(null)
          setUnavailable({ kind: 'error' })
          return
        }
        // `total` and the page itself are separate backend statements. A case opened or purged
        // between them can leave an in-range page empty; walk back once for an authoritative one.
        const lastPage = data.total === 0 ? 0 : Math.floor((data.total - 1) / data.size)
        if (requestedPage > lastPage) {
          setPage(lastPage)
          return
        }
        setCases(data.items)
        setPagination({ total: data.total, page: data.page, size: data.size })
      }
      loadedPartyIdRef.current = scope
      setLoadedPartyId(scope)
    } catch {
      // Timeout / abort / network — the BFF or kyc-service didn't answer.
      if (loadedPartyIdRef.current !== scope) {
        setCases([])
        setPagination(null)
        loadedPartyIdRef.current = null
        setLoadedPartyId(null)
      }
      setUnavailable({ kind: 'unreachable' })
    } finally { setLoading(false) }
  }, [partyId, page])

  useEffect(() => { load() }, [load])

  const filtered = cases.filter(c =>
    !search || c.id.includes(search) || c.partyId.includes(search) || c.status.includes(search.toUpperCase())
  )

  // Derived from what the service SAID it served (`pagination`), never from the local page state:
  // the two disagree for the whole duration of a request, and the number an operator reads has to
  // describe the rows actually on screen.
  const rangeStart = !pagination || pagination.total === 0 || cases.length === 0
    ? 0
    : pagination.page * pagination.size + 1
  const rangeEnd = !pagination || pagination.total === 0 || cases.length === 0
    ? 0
    : Math.min(pagination.page * pagination.size + cases.length, pagination.total)
  const hasNextPage = pagination ? (pagination.page + 1) * pagination.size < pagination.total : false

  return (
    <div>
      <PageHeader
        title={t('KYC Případy', 'KYC Cases')}
        subtitle={t('Ověření totožnosti zákazníka — přehled případů', 'Know Your Customer verification cases')}
        icon={<ShieldCheck size={20} aria-hidden="true" />}
        actions={<button
          type="button"
          className="btn btn-secondary"
          onClick={() => void load()}
          disabled={loading}
          aria-busy={loading}
          aria-label={t('Obnovit KYC případy', 'Refresh KYC cases')}
        >
          <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
          {t('Obnovit', 'Refresh')}
        </button>}
      />

      <PartySearch
        selectedId={selectedParty?.id}
        busy={loading}
        onSelect={party => { setSelectedParty(party); setPartyIdInput(party.id); setPage(0); setPartyId(party.id) }}
        placeholder={t('Jméno, příjmení, firma nebo Party UUID', 'Name, company, or Party UUID')}
      />

      {/* Case-local filter */}
      <div style={{ display: 'flex', gap: '10px', marginBottom: '16px' }}>
        <div style={{ position: 'relative', flex: 1, maxWidth: '300px' }}>
          <Search aria-hidden="true" size={14} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
          <label className="sr-only" htmlFor="kyc-search">{t('Hledat KYC případy', 'Search KYC cases')}</label>
          <input id="kyc-search" className="input" style={{ paddingLeft: '32px', width: '100%' }} placeholder={t('Filtrovat načtené případy (ID / stav)…', 'Filter loaded cases (ID / status)…')} value={search} onChange={e => setSearch(e.target.value)} />
        </div>
        <label className="sr-only" htmlFor="kyc-party-id">{t('Filtrovat podle Party ID', 'Filter by Party ID')}</label>
        <input id="kyc-party-id" className="input" style={{ width: '280px', fontFamily: 'var(--font-mono)', fontSize: '12px' }} placeholder={t('Filtrovat podle Party ID (UUID)…', 'Filter by Party ID (UUID)…')} value={partyIdInput} onChange={e => setPartyIdInput(e.target.value)} />
        <button
          type="button"
          className="btn btn-secondary"
          onClick={() => {
            const nextPartyId = partyIdInput.trim()
            setPage(0)
            if (nextPartyId === partyId) void load(nextPartyId, 0)
            else setPartyId(nextPartyId)
          }}
          disabled={loading || partyIdInput.trim() === ''}
          aria-busy={loading}
          aria-label={t('Vyhledat KYC případy', 'Search KYC cases')}
        >{t('Hledat', 'Search')}</button>
        {(selectedParty || partyId) && <button type="button" className="btn btn-secondary" onClick={() => { setSelectedParty(null); setPartyIdInput(''); setPage(0); setPartyId('') }}>{t('Všechny případy', 'All cases')}</button>}
      </div>

      {unavailable && (
        <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
          <DataUnavailable
            kind={unavailable.kind}
            service={t('KYC-service', 'KYC-service')}
            feature={t('KYC případy', 'KYC cases')}
            lang={language}
            detail={unavailable.kind === 'no_data' && partyId
              ? t('Pro tuto party nebyl nalezen žádný KYC případ.', 'No KYC case was found for this party.')
              : cases.length > 0 && loadedPartyId === (partyId || null)
                ? t('Zobrazen je poslední ověřený snapshot pro tento filtr; novější změny mohou chybět.', 'The last verified snapshot for this filter is shown; newer changes may be missing.')
                : undefined}
            dense
          />
        </div>
      )}

      <div className="card" aria-busy={loading} style={{ overflow: 'hidden' }}>
        <table className="data-table">
          <thead>
            <tr>
              <th>{t('ID případu', 'Case ID')}</th>
              <th>{t('Party ID', 'Party ID')}</th>
              <th>{t('Stav', 'Status')}</th>
              <th>{t('Ověření', 'Checks')}</th>
              <th>{t('Zkontroloval', 'Reviewed By')}</th>
              <th>{t('Aktualizováno', 'Updated')}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {loading && Array.from({ length: 5 }).map((_, i) => (
              <tr key={i}>{Array.from({ length: 7 }).map((_, j) => <td key={j}><div className="skeleton" style={{ height: '14px', width: j === 0 ? '200px' : '80px' }} /></td>)}</tr>
            ))}
            {!loading && !unavailable && filtered.length === 0 && (
              <tr><td colSpan={7} style={{ padding: 0 }}>
                <DataUnavailable
                  kind="no_data"
                  feature={t('KYC případy', 'KYC cases')}
                  lang={language}
                  detail={partyId
                    ? t('Pro tuto party nebyl nalezen žádný KYC případ.', 'No KYC case was found for this party.')
                    : t('Žádné KYC případy nenalezeny.', 'No KYC cases found.')}
                  dense
                />
              </td></tr>
            )}
            {!loading && filtered.map(c => (
              <tr key={c.id}>
                <td style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>{c.id.slice(0, 8)}…</td>
                <td style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>
                  <Can permission="parties:view"><Link href={`/parties/${c.partyId}`} style={{ color: 'var(--accent)', textDecoration: 'none' }}>{c.partyId.slice(0, 8)}…</Link></Can>
                </td>
                <td>
                  <StatusBadge status={c.status} />
                </td>
                <td>
                  <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap' }}>
                    {c.checks?.map(ch => (
                      <StatusBadge key={ch.checkType} status={ch.status} label={ch.checkType?.replace(/_/g, ' ') ?? ch.checkType} />
                    ))}
                  </div>
                </td>
                <td style={{ color: 'var(--text-muted)', fontSize: '12px' }}>{c.reviewedBy ?? '—'}</td>
                <td style={{ color: 'var(--text-muted)', fontSize: '12px' }}>{new Date(c.updatedAt).toLocaleDateString(dateLocale)}</td>
                <td>
                  <Can permission="parties:view"><Link href={`/parties/${c.partyId}`} style={{ color: 'var(--accent)', display: 'flex', alignItems: 'center', gap: '2px', fontSize: '12px', textDecoration: 'none' }}>
                    {t('Klient', 'Party')} <ChevronRight size={12} />
                  </Link></Can>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {/*
          Only the collection route is paginated — the party-scoped one answers a single case, so
          `pagination` is null there and no pager renders.
        */}
        {pagination && pagination.total > 0 && !unavailable && (
          <nav
            aria-label={t('Stránkování KYC případů', 'KYC cases pagination')}
            style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', padding: '12px 16px', borderTop: '1px solid var(--border)' }}
          >
            <button
              className="btn btn-secondary"
              type="button"
              aria-label={t('Předchozí stránka KYC případů', 'Previous KYC cases page')}
              disabled={loading || page === 0}
              onClick={() => setPage(current => Math.max(0, current - 1))}
            >
              {t('← Předchozí', '← Previous')}
            </button>
            <span role="status" aria-live="polite" style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
              {t(
                `Zobrazeno ${rangeStart}–${rangeEnd} z ${pagination.total} případů`,
                `Showing ${rangeStart}–${rangeEnd} of ${pagination.total} ${pagination.total === 1 ? 'case' : 'cases'}`,
              )}
            </span>
            <button
              className="btn btn-secondary"
              type="button"
              aria-label={t('Další stránka KYC případů', 'Next KYC cases page')}
              disabled={loading || !hasNextPage}
              onClick={() => setPage(current => current + 1)}
            >
              {t('Další →', 'Next →')}
            </button>
          </nav>
        )}
      </div>
    </div>
  )
}
