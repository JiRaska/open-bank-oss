// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Resolve a HUMAN-READABLE name to a party id.
//
// Why this exists: several console pages are keyed by party id because the thing they read is keyed
// that way — the analytics silver layer by aggregate id, consent-service by partyId. Exposing that
// key as the search box let the data model dictate the UX: an operator has a name in hand, not a
// UUID, and a UUID-only field is unusable without a second tab open on the Parties page.
//
// party-service owns identity, so name search belongs there — ADR-0055's trigram `/search`, reached
// through the BFF (ADR-0056). No page keeps its own name index, and this never becomes a second
// lookup path into the downstream store: it resolves a name to an id, and the id is what the caller
// queries with.
//
// Shared deliberately (ADR-0208): two callers need it (Customer 360, Consents), and a copy in each
// would be two divergent search behaviours over one endpoint.

import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Search } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { StatusBadge } from '@/components/ui'

const PARTY_SERVICE = '/api/svc/party-service'
const SEARCH_TIMEOUT_MS = 8_000

export const PARTY_UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export interface PartyHit {
  id: string
  legalName?: string | null
  tradingName?: string | null
  email?: string | null
  status?: string | null
  kycStatus?: string | null
}

export function partyDisplayName(p: PartyHit): string {
  return p.legalName || p.tradingName || p.id
}

interface Props {
  /** Rendered inside the search row, before the input — e.g. a lens selector, so the caller does not
      need a second card holding one control. */
  leading?: ReactNode
  /** Called with the chosen party. A pasted UUID is passed through as `{ id }` with no name. */
  onSelect: (party: PartyHit) => void
  /** Highlighted row, so the caller's current selection stays visible in the result list. */
  selectedId?: string
  /** Disables selection while the caller is loading the party's data. */
  busy?: boolean
  placeholder?: string
}

export function PartySearch({ onSelect, selectedId, busy = false, placeholder, leading }: Props) {
  const { t, language } = useLanguage()
  const [term, setTerm] = useState('')
  const [hits, setHits] = useState<PartyHit[] | null>(null)
  const [failure, setFailure] = useState<UnavailableKind | null>(null)
  const [searching, setSearching] = useState(false)
  // Only the newest search may commit its hits — otherwise a slow query for "Nov" lands after a fast
  // one for "Svoboda" and the operator picks from a list that does not match what they typed.
  const generation = useRef(0)
  const activeRequest = useRef<AbortController | null>(null)

  useEffect(() => () => {
    generation.current += 1
    activeRequest.current?.abort()
  }, [])

  const run = async () => {
    const q = term.trim()
    const gen = ++generation.current
    activeRequest.current?.abort()
    activeRequest.current = null
    if (!q) {
      setSearching(false)
      return
    }
    // A pasted party id is not a name — skip the trigram search entirely, so an operator who already
    // has an id keeps the direct path instead of being forced through a result list of one.
    if (PARTY_UUID_RE.test(q)) {
      setSearching(false)
      setHits(null)
      setFailure(null)
      onSelect({ id: q })
      return
    }
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), SEARCH_TIMEOUT_MS)
    activeRequest.current = controller
    setSearching(true)
    setFailure(null)
    setHits(null)
    try {
      const res = await fetch(
        `${PARTY_SERVICE}/api/v1/parties/search?q=${encodeURIComponent(q)}&limit=20`,
        { cache: 'no-store', signal: controller.signal },
      )
      if (gen !== generation.current) return // superseded by a newer search
      if (!res.ok) {
        setFailure(res.status === 401 || res.status === 403 ? 'unauthorized' : 'unreachable')
        return
      }
      const body = (await res.json()) as { data?: PartyHit[] }
      if (gen !== generation.current) return
      setHits(body.data ?? [])
    } catch {
      if (gen === generation.current) setFailure('unreachable')
    } finally {
      window.clearTimeout(timeout)
      if (activeRequest.current === controller) activeRequest.current = null
      if (gen === generation.current) setSearching(false)
    }
  }

  return (
    <>
      <div className="card" role="search" aria-label={t('Vyhledání klienta nebo firmy', 'Find a customer or company')} aria-busy={searching} style={{ marginBottom: '20px', padding: '20px' }}>
        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
          {leading}
          <input
            value={term}
            onChange={e => setTerm(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') run() }}
            placeholder={placeholder ?? t('Jméno nebo název firmy (nebo UUID party)', 'Name or company name (or party UUID)')}
            aria-label={t('Vyhledat stranu', 'Search parties')}
            aria-controls="party-search-results"
            style={{
              flex: '1 1 340px', padding: '8px 12px', borderRadius: '8px', border: '1px solid var(--border)',
              background: 'var(--surface)', color: 'var(--text-primary)', fontSize: '13px',
            }}
          />
          <button
            type="button"
            onClick={run}
            disabled={searching || busy || !term.trim()}
            aria-busy={searching}
            style={{
              display: 'flex', alignItems: 'center', gap: '6px', padding: '8px 16px',
              cursor: searching || busy || !term.trim() ? 'not-allowed' : 'pointer', borderRadius: '8px',
              border: '1px solid var(--border)', background: 'var(--surface)',
              color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600,
              opacity: searching || busy || !term.trim() ? 0.6 : 1,
            }}
          >
            <Search size={15} aria-hidden="true" /> {searching ? t('Hledám…', 'Searching…') : t('Vyhledat', 'Search')}
          </button>
        </div>
        <p style={{ margin: '10px 0 0', fontSize: '11px', color: 'var(--text-secondary)' }}>
          {t(
            'Začněte jménem klienta nebo názvem firmy. Pokud už máte Party ID, můžete ho vložit přímo.',
            'Start with the customer or company name. If you already have a Party ID, paste it directly.',
          )}
        </p>
      </div>

      <div id="party-search-results" aria-live="polite">
        {searching && (
          <div role="status" style={{ color: 'var(--text-secondary)', padding: '24px', textAlign: 'center' }}>
            {t('Hledám…', 'Searching…')}
          </div>
        )}

        {!searching && failure && (
          <DataUnavailable
            kind={failure}
            service="party-service"
            feature={t('Hledání party', 'Party search')}
            lang={language === 'cs' ? 'cs' : 'en'}
          />
        )}

        {!searching && hits && hits.length === 0 && (
          <div className="card" style={{ padding: '28px', textAlign: 'center', marginBottom: '20px' }}>
          {/* Stated as a search result, not as an unavailable data source: party-service answered. */}
          <p style={{ margin: 0, fontWeight: 600, color: 'var(--text-primary)' }}>
            {t('Žádná party neodpovídá hledání', 'No party matches that search')}
          </p>
          <p style={{ margin: '6px 0 0', fontSize: '12px', color: 'var(--text-secondary)' }}>
            {t('party-service odpověděla — hledaný výraz nic nenašel.', 'party-service answered — the term matched nothing.')}
          </p>
          </div>
        )}

        {!searching && hits && hits.length > 0 && (
          <div className="card" style={{ marginBottom: '20px', overflowX: 'auto', padding: '20px' }}>
          <h2 className="section-title" style={{ marginBottom: '12px' }}>
            {t('Nalezené party', 'Matching parties')} ({hits.length})
          </h2>
          <table className="table">
            <thead>
              <tr>
                <th>{t('Jméno', 'Name')}</th>
                <th>{t('E-mail', 'Email')}</th>
                <th>{t('Stav', 'Status')}</th>
                <th>KYC</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {hits.map(p => (
                <tr key={p.id} style={{ background: selectedId === p.id ? 'var(--surface-hover)' : undefined }}>
                  <td style={{ fontWeight: 600 }}>{partyDisplayName(p)}</td>
                  <td style={{ color: 'var(--text-secondary)' }}>{p.email || '—'}</td>
                  <td>{p.status ? <StatusBadge status={p.status} withDot /> : '—'}</td>
                  <td>{p.kycStatus ? <StatusBadge status={p.kycStatus} /> : '—'}</td>
                  <td style={{ textAlign: 'right' }}>
                    <button
                      type="button"
                      onClick={() => onSelect(p)}
                      disabled={busy}
                      aria-pressed={selectedId === p.id}
                      aria-label={t(`Vybrat ${partyDisplayName(p)}`, `Select ${partyDisplayName(p)}`)}
                      style={{
                        padding: '5px 12px', borderRadius: '6px', border: '1px solid var(--border)',
                        background: 'var(--surface)', color: 'var(--text-secondary)', fontSize: '12px',
                        fontWeight: 600, cursor: busy ? 'not-allowed' : 'pointer',
                      }}
                    >
                      {t('Vybrat', 'Select')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </div>
    </>
  )
}
