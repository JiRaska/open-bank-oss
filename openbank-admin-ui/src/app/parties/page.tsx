// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import Link from 'next/link'
import { Users, Plus, Search, RefreshCw, ChevronRight, ChevronDown } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { Can } from '@/components/auth/AuthGuard'
import { parsePartyListPage, type PartyListItem, type PartyListPage } from '@/lib/party/partyListContract'

const PAGE_SIZE = 25

type Party = PartyListItem
type PartyListMeta = Omit<PartyListPage, 'items'>

interface Pagination {
  limit: number
  hasNextPage: boolean
  nextCursor?: string
}

export default function PartiesPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'

  // ── list mode (no search term) ──────────────────────────────────────────────
  const [parties, setParties]         = useState<Party[]>([])
  const [loading, setLoading]         = useState(true)
  const [listLoadingMore, setListLoadingMore] = useState(false)
  const [listPage, setListPage] = useState<PartyListMeta | null>(null)
  const [listPageError, setListPageError] = useState<UnavailableKind | null>(null)
  const listGeneration = useRef(0)
  // Typed unavailable reason → renders the calm <DataUnavailable> panel instead
  // of a raw "HTTP 404" leak (admin-ui graceful-state rule). party-service may
  // not be deployed here — we degrade to an explained empty state.
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  // ── search mode (q ≥ 2 chars) — ADR-0055 Phase 4 BFF wiring ────────────────
  const [search, setSearch]               = useState('')
  const [debouncedQ, setDebouncedQ]       = useState('')
  const [searchRows, setSearchRows]       = useState<Party[]>([])
  const [searchPagi, setSearchPagi]       = useState<Pagination | null>(null)
  const [searching, setSearching]         = useState(false)
  const [loadingMore, setLoadingMore]     = useState(false)
  const [searchUnavail, setSearchUnavail] = useState<{ kind: UnavailableKind } | null>(null)
  const searchGeneration = useRef(0)

  const purgeAuthorizedEvidence = useCallback(() => {
    listGeneration.current += 1
    searchGeneration.current += 1
    setParties([])
    setListPage(null)
    setListPageError(null)
    setSearchRows([])
    setSearchPagi(null)
    setLoading(false)
    setListLoadingMore(false)
    setSearching(false)
    setLoadingMore(false)
    setUnavailable({ kind: 'unauthorized' })
    setSearchUnavail({ kind: 'unauthorized' })
  }, [])

  // ── derived ─────────────────────────────────────────────────────────────────
  const inSearchMode  = debouncedQ.length >= 2
  const showHint      = search.length > 0 && search.length < 2
  const displayRows   = inSearchMode ? searchRows  : parties
  const displayLoad   = inSearchMode ? searching   : loading
  const displayUnavail = inSearchMode ? searchUnavail : unavailable

  // ── list load ───────────────────────────────────────────────────────────────
  const load = useCallback(async (page = 0, append = false) => {
    const generation = ++listGeneration.current
    if (append) setListLoadingMore(true)
    else setLoading(true)
    setListPageError(null)
    if (!append) setUnavailable(null)
    try {
      const res = await fetch(
        svcUrl('party-service', '/api/v1/parties', { page: String(page), size: String(PAGE_SIZE) }),
        { signal: AbortSignal.timeout(5000) }
      )
      if (!res.ok) {
        // A collection with no parties is 200 + items:[]. A 404 therefore means the
        // service/route is absent, never a truthful empty registry; 405 is contract drift.
        const classified = await classifyBffFailure(res)
        const kind = res.status === 405 || classified === 'not_found' ? 'error' : classified
        if (generation !== listGeneration.current) return
        if (kind === 'unauthorized') {
          purgeAuthorizedEvidence()
          return
        }
        if (append) setListPageError(kind)
        else { setParties([]); setListPage(null); setUnavailable({ kind }) }
        return
      }
      const data = await res.json().catch(() => null)
      const parsed = parsePartyListPage(data, page)
      if (generation !== listGeneration.current) return
      if (!parsed) {
        if (append) setListPageError('error')
        else { setParties([]); setListPage(null); setUnavailable({ kind: 'error' }) }
        return
      }
      setParties(previous => append
        ? [...previous, ...parsed.items.filter(item => !previous.some(existing => existing.id === item.id))]
        : parsed.items)
      setListPage({ total: parsed.total, page: parsed.page, size: parsed.size })
    } catch {
      // Timeout / abort / network — BFF or party-service didn't answer
      if (generation !== listGeneration.current) return
      if (append) setListPageError('unreachable')
      else { setParties([]); setListPage(null); setUnavailable({ kind: 'unreachable' }) }
    } finally {
      if (generation === listGeneration.current) {
        if (append) setListLoadingMore(false)
        else setLoading(false)
      }
    }
  }, [purgeAuthorizedEvidence])

  // ── name search — ADR-0055 (first correct SearchRequest adopter in fleet) ───
  const runSearch = useCallback(async (q: string, cursor?: string) => {
    const generation = ++searchGeneration.current
    if (!cursor) { setSearching(true); setSearchRows([]) } else setLoadingMore(true)
    setSearchUnavail(null)
    try {
      const params: Record<string, string> = { q, limit: String(PAGE_SIZE) }
      if (cursor) params.cursor = cursor
      const res = await fetch(
        svcUrl('party-service', '/api/v1/parties/search', params),
        { signal: AbortSignal.timeout(5000) }
      )
      if (!res.ok) {
        const kind = await classifyBffFailure(res)
        if (generation !== searchGeneration.current) return
        if (kind === 'unauthorized') {
          purgeAuthorizedEvidence()
          return
        }
        setSearchUnavail({ kind })
        return
      }
      const data = await res.json()
      if (generation !== searchGeneration.current) return
      // Response: { data: Party[], pagination: { limit, hasNextPage, nextCursor? } }
      // GDPR: server enforces toSimpleResponse() — no phone/address/DOB returned
      const rows: Party[] = data.data ?? []
      setSearchRows(prev => cursor ? [...prev, ...rows] : rows)
      setSearchPagi(data.pagination ?? null)
    } catch {
      if (generation !== searchGeneration.current) return
      setSearchUnavail({ kind: 'unreachable' })
    } finally {
      if (generation === searchGeneration.current) {
        setSearching(false)
        setLoadingMore(false)
      }
    }
  }, [purgeAuthorizedEvidence])

  // ── debounce 300 ms ─────────────────────────────────────────────────────────
  useEffect(() => {
    const id = setTimeout(() => setDebouncedQ(search), 300)
    return () => clearTimeout(id)
  }, [search])

  // ── fire search when debounced query changes ────────────────────────────────
  useEffect(() => {
    void Promise.resolve().then(() => {
      if (debouncedQ.length >= 2) {
        void runSearch(debouncedQ)
      } else {
        searchGeneration.current += 1
        setSearchRows([]); setSearchPagi(null); setSearchUnavail(null)
      }
    })
  }, [debouncedQ, runSearch])

  // ── initial list load ───────────────────────────────────────────────────────
  useEffect(() => { void Promise.resolve().then(() => load()) }, [load])

  return (
    <div>
      <PageHeader
        icon={<Users size={18} aria-hidden="true" />}
        title={t('Subjekty', 'Parties')}
        subtitle={t('Zákazníci a společnosti registrované v platformě', 'Customers and companies registered in the platform')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Subjekty', 'Parties')}</span></div>}
        actions={<div style={{ display: 'flex', gap: '8px' }}>
          <button className="btn btn-secondary" type="button" onClick={() => load()} disabled={loading || inSearchMode}
            aria-busy={loading} aria-label={t('Obnovit subjekty', 'Refresh parties')}>
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
          <Can permission="parties:create">
            <Link href="/parties/new" className="btn btn-primary" style={{ display: 'flex', alignItems: 'center', gap: '6px', textDecoration: 'none' }}>
              <Plus size={13} aria-hidden="true" /> {t('Nový subjekt', 'New Party')}
            </Link>
          </Can>
        </div>}
      />

      {/* Search toolbar */}
      <div style={{ display: 'flex', gap: '10px', marginBottom: '16px', alignItems: 'center' }}>
        <div style={{ position: 'relative', flex: 1, maxWidth: '360px' }}>
          <Search size={14} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
          <input
            id="party-search"
            className="input"
            aria-label={t('Vyhledat subjekt podle jména', 'Search parties by name')}
            style={{ paddingLeft: '32px', width: '100%' }}
            placeholder={t('Hledat podle jména (min. 2 znaky)…', 'Search by name (min. 2 chars)…')}
            value={search}
            onChange={e => setSearch(e.target.value)}
          />
        </div>
        {showHint && (
          <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            {t('Zadejte alespoň 2 znaky', 'Enter at least 2 characters')}
          </span>
        )}
        {inSearchMode && !searching && (
          <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            {t(`${searchRows.length} výsledků`, `${searchRows.length} result${searchRows.length !== 1 ? 's' : ''}`)}
          </span>
        )}
        {!inSearchMode && !loading && listPage && (
          <span style={{ fontSize: '12px', color: 'var(--text-muted)' }} role="status">
            {t(`Načteno ${parties.length} z ${listPage.total}`, `Loaded ${parties.length} of ${listPage.total}`)}
          </span>
        )}
        {inSearchMode && searching && (
          <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            {t('Hledám…', 'Searching…')}
          </span>
        )}
      </div>

      {displayUnavail && (
        <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
          <DataUnavailable
            kind={displayUnavail.kind}
            service={t('Party-service', 'Party-service')}
            feature={t('Subjekty', 'Parties')}
            lang={language}
            dense
          />
        </div>
      )}

      {!displayUnavail && (
        <div className="card" style={{ overflow: 'hidden' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>{t('Obchodní jméno', 'Legal Name')}</th>
                <th>{t('Typ', 'Type')}</th>
                <th>{t('E-mail', 'Email')}</th>
                <th>{t('Stav', 'Status')}</th>
                <th>KYC</th>
                <th>{t('Vytvořeno', 'Created')}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {displayLoad && Array.from({ length: 5 }).map((_, i) => (
                <tr key={i}>
                  {Array.from({ length: 7 }).map((_, j) => (
                    <td key={j}><div className="skeleton" style={{ height: '14px', width: j === 0 ? '140px' : '80px' }} /></td>
                  ))}
                </tr>
              ))}
              {!displayLoad && displayRows.length === 0 && (
                <tr>
                  <td colSpan={7} style={{ textAlign: 'center', padding: '40px', color: 'var(--text-muted)' }}>
                    {inSearchMode
                      ? t('Žádné subjekty neodpovídají vašemu hledání', 'No parties match your search')
                      : t('Žádné subjekty nenalezeny — vytvořte první', 'No parties found — create the first one')}
                  </td>
                </tr>
              )}
              {!displayLoad && displayRows.map(p => (
                <tr key={p.id}>
                  <td style={{ fontWeight: 500 }}>
                    {p.legalName}
                    {p.tradingName && p.tradingName !== p.legalName && (
                      <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '2px' }}>{p.tradingName}</div>
                    )}
                  </td>
                  <td><span className="tag">{p.partyType}</span></td>
                  <td style={{ color: 'var(--text-secondary)', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{p.email}</td>
                  <td><StatusBadge status={p.status} /></td>
                  <td><StatusBadge status={p.kycStatus} label={p.kycStatus?.replace('_', ' ')} /></td>
                  <td style={{ color: 'var(--text-muted)', fontSize: '12px' }}>{new Date(p.createdAt).toLocaleDateString(dateLocale)}</td>
                  <td>
                    <Link href={`/parties/${p.id}`} style={{ color: 'var(--accent)', display: 'flex', alignItems: 'center', gap: '2px', fontSize: '12px', textDecoration: 'none' }}>
                      {t('Detail', 'View')} <ChevronRight size={12} />
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Cursor-page Load more (ADR-0055) */}
          {inSearchMode && searchPagi?.hasNextPage && !loadingMore && (
            <div style={{ padding: '12px 20px', borderTop: '1px solid var(--border)' }}>
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => runSearch(debouncedQ, searchPagi.nextCursor)}
                aria-label={t('Načíst další subjekty', 'Load more parties')}
              >
                <ChevronDown size={13} aria-hidden="true" />
                {t('Načíst další', 'Load more')}
              </button>
            </div>
          )}
          {loadingMore && (
            <div style={{ padding: '12px 20px', borderTop: '1px solid var(--border)', color: 'var(--text-muted)', fontSize: '13px' }}>
              {t('Načítám…', 'Loading…')}
            </div>
          )}
          {!inSearchMode && listPage && (listPage.page + 1) * listPage.size < listPage.total && !listLoadingMore && !listPageError && (
            <div style={{ padding: '12px 20px', borderTop: '1px solid var(--border)' }}>
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => load(listPage.page + 1, true)}
                aria-label={t('Načíst další subjekty ze seznamu', 'Load more parties from the list')}
              >
                <ChevronDown size={13} aria-hidden="true" />
                {t('Načíst další', 'Load more')}
              </button>
            </div>
          )}
          {!inSearchMode && listLoadingMore && (
            <div role="status" style={{ padding: '12px 20px', borderTop: '1px solid var(--border)', color: 'var(--text-muted)', fontSize: '13px' }}>
              {t('Načítám další subjekty…', 'Loading more parties…')}
            </div>
          )}
          {!inSearchMode && listPageError && listPage && (
            <DataUnavailable
              kind={listPageError}
              service={t('Party-service', 'Party-service')}
              feature={t('Další strana subjektů', 'Next party page')}
              lang={language}
              dense
            >
              <button type="button" className="btn btn-secondary" onClick={() => load(listPage.page + 1, true)}>
                {t('Zkusit znovu', 'Retry')}
              </button>
            </DataUnavailable>
          )}
        </div>
      )}
    </div>
  )
}
