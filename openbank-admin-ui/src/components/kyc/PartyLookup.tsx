// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Resolve a KYC customer by name, company or party UUID through party-service (#5904).
// The lookup itself lives in lib/party/resolveParty; this renders its outcomes so that a
// lookup which FAILED never looks like a lookup that found nothing.

'use client'

import { useCallback, useState } from 'react'
import { Search, Loader2, SearchX, ServerOff } from 'lucide-react'
import { resolveParty, partyLabel, MIN_TERM_LENGTH, type PartyResolution, type PartyLookupFailure } from '@/lib/party/resolveParty'

interface Props {
  onSelect: (partyId: string) => void
  lang?: 'cs' | 'en'
  /** Injected in tests; defaults to the real resolver. */
  resolve?: typeof resolveParty
}

function failureCopy(reason: PartyLookupFailure, cs: boolean): string {
  switch (reason) {
    case 'search_unavailable':
      return cs
        ? 'Vyhledávání podle jména je právě nedostupné (party-service vrátila 404 — schopnost party-search je vypnutá). Toto NENÍ důkaz, že zákazník neexistuje.'
        : 'Name search is unavailable right now (party-service answered 404 — the party-search capability is off). This is NOT evidence that no such customer exists.'
    case 'not_deployed':
      return cs
        ? 'Party-service není v tomto prostředí nasazená — vyhledání zákazníka neproběhlo.'
        : 'Party-service is not deployed in this environment — the lookup did not run.'
    case 'scaled_to_zero':
      return cs
        ? 'Party-service je uspaná (scale-to-zero) — vyhledání neproběhlo. Zkus to za chvíli znovu.'
        : 'Party-service is idle (scaled to zero) — the lookup did not run. Retry in a moment.'
    case 'unauthorized':
      return cs
        ? 'Chybí oprávnění pro čtení party-service — vyhledání neproběhlo.'
        : 'Not authorised to read party-service — the lookup did not run.'
    case 'unreachable':
      return cs
        ? 'Party-service neodpověděla (timeout nebo síťová chyba) — vyhledání neproběhlo.'
        : 'Party-service did not answer (timeout or network error) — the lookup did not run.'
    default:
      return cs
        ? 'Vyhledání zákazníka selhalo — výsledek nelze považovat za prázdný.'
        : 'The customer lookup failed — the result must not be read as empty.'
  }
}

export function PartyLookup({ onSelect, lang = 'en', resolve = resolveParty }: Props) {
  const cs = lang === 'cs'
  const [query, setQuery] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<PartyResolution>({ status: 'idle' })

  const run = useCallback(async () => {
    setBusy(true)
    try {
      setResult(await resolve(query))
    } finally {
      setBusy(false)
    }
  }, [query, resolve])

  return (
    <div data-testid="party-lookup">
      <div style={{ display: 'flex', gap: '10px', marginBottom: '12px' }}>
        <div style={{ position: 'relative', flex: 1, maxWidth: '420px' }}>
          <Search size={14} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
          <input
            className="input"
            style={{ paddingLeft: '32px', width: '100%' }}
            aria-label={cs ? 'Najít zákazníka podle jména, firmy nebo UUID' : 'Find a customer by name, company or UUID'}
            placeholder={cs ? 'Jméno, firma nebo Party UUID…' : 'Name, company or party UUID…'}
            value={query}
            onChange={e => setQuery(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') void run() }}
          />
        </div>
        <button className="btn btn-secondary" onClick={() => void run()} disabled={busy}>
          {busy ? <Loader2 size={13} aria-hidden="true" /> : null}
          {cs ? 'Najít zákazníka' : 'Find customer'}
        </button>
      </div>

      {busy && (
        <div data-testid="lookup-state" data-state="loading" className="card" style={{ padding: 12, marginBottom: 12, fontSize: 13, color: 'var(--text-secondary)' }}>
          {cs ? 'Hledám v party-service…' : 'Looking up party-service…'}
        </div>
      )}

      {!busy && result.status === 'too_short' && (
        <div data-testid="lookup-state" data-state="too_short" className="card" style={{ padding: 12, marginBottom: 12, fontSize: 13, color: 'var(--text-secondary)' }}>
          {cs
            ? `Zadej aspoň ${MIN_TERM_LENGTH} znaky — party-service kratší dotaz odmítá a vrátila by prázdnou stránku.`
            : `Enter at least ${MIN_TERM_LENGTH} characters — party-service rejects a shorter term and would return an empty page.`}
        </div>
      )}

      {!busy && result.status === 'none' && (
        <div data-testid="lookup-state" data-state="none" className="card" style={{ padding: 12, marginBottom: 12, fontSize: 13, color: 'var(--text-secondary)', display: 'flex', gap: 8, alignItems: 'center' }}>
          <SearchX size={15} aria-hidden="true" />
          {result.mode === 'uuid'
            ? (cs ? 'Party s tímto UUID v party-service neexistuje.' : 'No party with this UUID exists in party-service.')
            : (cs ? 'Vyhledávání proběhlo a neodpovídá mu žádný zákazník.' : 'The search ran and no customer matches.')}
        </div>
      )}

      {!busy && result.status === 'failed' && (
        <div data-testid="lookup-state" data-state="failed" className="card" style={{ padding: 12, marginBottom: 12, fontSize: 13, color: '#92400e', background: '#fffbeb', border: '1px solid #fcd34d', display: 'flex', gap: 8, alignItems: 'center' }}>
          <ServerOff size={15} aria-hidden="true" />
          {failureCopy(result.reason, cs)}
        </div>
      )}

      {!busy && result.status === 'ok' && (
        <div data-testid="lookup-state" data-state="matches" className="card" style={{ padding: 0, marginBottom: 12, overflow: 'hidden' }}>
          {result.matches.map(m => (
            <button
              key={m.id}
              data-testid="party-match"
              onClick={() => onSelect(m.id)}
              style={{ display: 'block', width: '100%', textAlign: 'left', padding: '10px 14px', border: 0, borderTop: '1px solid var(--border)', background: 'transparent', cursor: 'pointer' }}
            >
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' }}>{partyLabel(m)}</div>
              <div style={{ fontSize: 11, color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)' }}>
                {m.id}{m.kycStatus ? ` · KYC ${m.kycStatus}` : ''}
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
