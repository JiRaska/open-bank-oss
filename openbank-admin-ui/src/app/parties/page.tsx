// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import Link from 'next/link'
import { Users, Plus, Search, RefreshCw, ChevronRight, ChevronDown } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { Can } from '@/components/auth/AuthGuard'

const PAGE_SIZE = 25

interface Party {
  id: string
  partyType: string
  status: string
  legalName: string
  tradingName?: string
  email: string
  phone?: string
  kycStatus: string
  createdAt: string
}

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

  // ── derived ─────────────────────────────────────────────────────────────────
  const inSearchMode  = debouncedQ.length >= 2
  const showHint      = search.length > 0 && search.length < 2
  const displayRows   = inSearchMode ? searchRows  : parties
  const displayLoad   = inSearchMode ? searching   : loading
  const displayUnavail = inSearchMode ? searchUnavail : unavailable

  // ── list load ───────────────────────────────────────────────────────────────
  const load = useCallback(async () => {
    setLoading(true); setUnavailable(null)
    try {
      const res = await fetch(
        svcUrl('party-service', '/api/v1/parties'),
        { signal: AbortSignal.timeout(5000) }
      )
      if (!res.ok) {
        // 404/405 → list endpoint not yet deployed; degrade to empty, not error
        if (res.status === 404 || res.status === 405) {
          setParties([])
        } else {
          setParties([])
          setUnavailable({ kind: await classifyBffFailure(res) })
        }
        return
      }
      const data = await res.json()
      setParties(Array.isArray(data) ? data : data.items ?? data.content ?? [])
    } catch {
      // Timeout / abort / network — BFF or party-service didn't answer
      setParties([])
      setUnavailable({ kind: 'unreachable' })
    } finally { setLoading(false) }
  }, [])

  // ── name search — ADR-0055 (first correct SearchRequest adopter in fleet) ───
  const runSearch = useCallback(async (q: string, cursor?: string) => {
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
        setSearchUnavail({ kind: await classifyBffFailure(res) })
        return
      }
      const data = await res.json()
      // Response: { data: Party[], pagination: { limit, hasNextPage, nextCursor? } }
      // GDPR: server enforces toSimpleResponse() — no phone/address/DOB returned
      const rows: Party[] = data.data ?? []
      setSearchRows(prev => cursor ? [...prev, ...rows] : rows)
      setSearchPagi(data.pagination ?? null)
    } catch {
      setSearchUnavail({ kind: 'unreachable' })
    } finally { setSearching(false); setLoadingMore(false) }
  }, [])

  // ── debounce 300 ms ─────────────────────────────────────────────────────────
  useEffect(() => {
    const id = setTimeout(() => setDebouncedQ(search), 300)
    return () => clearTimeout(id)
  }, [search])

  // ── fire search when debounced query changes ────────────────────────────────
  useEffect(() => {
    if (debouncedQ.length >= 2) {
      runSearch(debouncedQ)
    } else {
      setSearchRows([]); setSearchPagi(null); setSearchUnavail(null)
    }
  }, [debouncedQ, runSearch])

  // ── initial list load ───────────────────────────────────────────────────────
  useEffect(() => { load() }, [load])

  return (
    <div>
      <PageHeader
        icon={<Users size={18} aria-hidden="true" />}
        title={t('Subjekty', 'Parties')}
        subtitle={t('Zákazníci a společnosti registrované v platformě', 'Customers and companies registered in the platform')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Subjekty', 'Parties')}</span></div>}
        actions={<div style={{ display: 'flex', gap: '8px' }}>
          <button className="btn btn-secondary" type="button" onClick={load} disabled={loading || inSearchMode}
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
        </div>
      )}
    </div>
  )
}
