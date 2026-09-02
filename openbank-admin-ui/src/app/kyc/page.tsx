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

/** Envelope returned by GET /api/v1/kyc/cases (KycResource.listCases, openapi.yaml KycCasePage). */
interface KycCasePage {
  items: KycCase[]
  total: number
  page: number
  size: number
  statusFilter: string | null
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
  // Server-backed pagination against the declared KycCasePage envelope (issue #8163) —
  // `page`/`total` are only meaningful in list mode; the party-scoped lookup returns a single case.
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState<number | null>(null)
  // Requests can race (e.g. a slow page-1 response landing after a page-2 click) — only the
  // most recently ISSUED request may commit state, never whichever happens to resolve first.
  const requestSeq = useRef(0)

  const load = useCallback(async (requestedPartyId = partyId, requestedPage = page) => {
    const scope = requestedPartyId || null
    const seq = ++requestSeq.current
    setLoading(true); setUnavailable(null)
    try {
      const url = requestedPartyId
        ? `${KYC_SERVICE}/api/v1/kyc/cases/party/${requestedPartyId}`
        : `${KYC_SERVICE}/api/v1/kyc/cases?page=${requestedPage}&size=${PAGE_SIZE}`
      const res = await fetch(url, { signal: AbortSignal.timeout(5000) })
      if (seq !== requestSeq.current) return // superseded by a newer request — drop this response
      if (!res.ok) {
        const kind = await classifyBffFailure(res)
        // Only a genuine 404 on the PARTY-SCOPED lookup means "no case exists for this party".
        // A 405 (or a 404 on the unscoped list route) is a route-contract failure — surfacing it
        // as an empty result would tell an operator the KYC queue is empty when it is actually
        // unavailable (issue #8163).
        const isPartyLookupMiss = Boolean(scope) && res.status === 404
        const unavailableKind = isPartyLookupMiss ? 'no_data' : kind
        if (unavailableKind === 'no_data' || loadedPartyIdRef.current !== scope) {
          setCases([]); setTotal(null)
          loadedPartyIdRef.current = unavailableKind === 'no_data' ? scope : null
          setLoadedPartyId(unavailableKind === 'no_data' ? scope : null)
        }
        setUnavailable({ kind: unavailableKind })
        return
      }
      const data = await res.json()
      if (requestedPartyId) {
        // Single-case response (KycResource.getCaseByParty) — not paginated.
        setCases(Array.isArray(data) ? data : [data].filter(Boolean))
        setTotal(null)
      } else {
        const envelope = data as Partial<KycCasePage>
        setCases(Array.isArray(envelope.items) ? envelope.items : [])
        setTotal(typeof envelope.total === 'number' ? envelope.total : null)
      }
      loadedPartyIdRef.current = scope
      setLoadedPartyId(scope)
    } catch {
      if (seq !== requestSeq.current) return
      // Timeout / abort / network — the BFF or kyc-service didn't answer.
      if (loadedPartyIdRef.current !== scope) {
        setCases([]); setTotal(null)
        loadedPartyIdRef.current = null
        setLoadedPartyId(null)
      }
      setUnavailable({ kind: 'unreachable' })
    } finally {
      if (seq === requestSeq.current) setLoading(false)
    }
  }, [partyId, page])

  useEffect(() => { load(partyId, page) }, [load, partyId, page])

  const canPrev = !partyId && page > 0
  const canNext = !partyId && total !== null && (page + 1) * PAGE_SIZE < total
  const rangeStart = total !== null && total > 0 ? page * PAGE_SIZE + 1 : 0
  const rangeEnd = total !== null ? Math.min((page + 1) * PAGE_SIZE, total) : cases.length

  const filtered = cases.filter(c =>
    !search || c.id.includes(search) || c.partyId.includes(search) || c.status.includes(search.toUpperCase())
  )

  return (
    <div>
      <PageHeader
        title={t('KYC Případy', 'KYC Cases')}
        subtitle={t('Ověření totožnosti zákazníka — přehled případů', 'Know Your Customer verification cases')}
        icon={<ShieldCheck size={20} aria-hidden="true" />}
        actions={<button
          type="button"
          className="btn btn-secondary"
          onClick={() => void load(partyId, page)}
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
        onSelect={party => { setSelectedParty(party); setPartyIdInput(party.id); setPartyId(party.id); setPage(0) }}
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
            if (nextPartyId === partyId) void load(nextPartyId, 0)
            else setPartyId(nextPartyId)
            setPage(0)
          }}
          disabled={loading || partyIdInput.trim() === ''}
          aria-busy={loading}
          aria-label={t('Vyhledat KYC případy', 'Search KYC cases')}
        >{t('Hledat', 'Search')}</button>
        {(selectedParty || partyId) && <button type="button" className="btn btn-secondary" onClick={() => { setSelectedParty(null); setPartyIdInput(''); setPartyId(''); setPage(0) }}>{t('Všechny případy', 'All cases')}</button>}
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
      </div>

      {!partyId && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: '10px' }}>
          <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            {total !== null
              ? t(`Zobrazeno ${rangeStart}–${rangeEnd} z ${total}`, `Showing ${rangeStart}-${rangeEnd} of ${total}`)
              : t('Celkový počet není znám', 'Total unknown')}
          </span>
          <div style={{ display: 'flex', gap: '8px' }}>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={loading || !canPrev}
              aria-label={t('Předchozí strana', 'Previous page')}
            >{t('Předchozí', 'Previous')}</button>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setPage(p => p + 1)}
              disabled={loading || !canNext}
              aria-label={t('Další strana', 'Next page')}
            >{t('Další', 'Next')}</button>
          </div>
        </div>
      )}
    </div>
  )
}
