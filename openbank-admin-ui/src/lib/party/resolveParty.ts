// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Resolve a KYC operator's free-text query to real parties through party-service
// (issue #5904: "Resolve KYC customers by name/company/UUID through party-service").
//
// WHY THIS IS A LOOKUP AND NOT A CLIENT-SIDE FILTER
// The KYC screen used to `Array.filter` the KYC cases it had already loaded and call the
// input a search box. That can only ever match a party id the operator already had on
// screen, and it can never find a customer BY NAME at all — party names are not in the
// KYC case payload. party-service owns the searchable business keys, so the lookup has to
// go there.
//
// WHAT PARTY-SERVICE ACTUALLY EXPOSES (verified against the resource + repository, not
// only against openapi.yaml):
//   GET /api/v1/parties/{id}     — PartyResource.getParty, exact id.
//   GET /api/v1/parties/search   — PartyResource.searchParties, `q` >= 2 chars,
//                                  cursor-paginated, case-insensitive substring over
//                                  legalName, tradingName, email, phone, taxId and
//                                  registrationNumber (PartyRepositoryImpl
//                                  .searchByBusinessKeys). So "by name" and "by company"
//                                  are both real server-side modes. Birth number is
//                                  deliberately NOT searchable (GDPR data-minimisation).
//
// THE ONE TRAP THAT DECIDES THE FAILURE STATES
// /search is annotated `@FeatureFlag(flag = "party-search")`. When that flag is off the
// libs interceptor throws FeatureDisabledException and party-service's FeatureDisabledMapper
// answers **404**. A search that legitimately matches nothing answers **200 with an empty
// page** — PartyService.searchParties never 404s for "no match". Therefore a 404 from
// /search means "this bank cannot search right now", never "this customer does not exist",
// and rendering it as an empty result would be exactly the "looks empty without enough
// evidence" failure #5904 exists to remove. The two outcomes get different states here.

import { svcUrl, classifyBffFailure, type BffFailure } from '@/lib/services/bff'

export const PARTY_SERVICE = 'party-service'

/** Minimum term length party-service accepts; below this it returns an empty page by design. */
export const MIN_TERM_LENGTH = 2

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export type PartyLookupMode = 'uuid' | 'term'

/** Data-minimised party summary — the shape /search and /{id} both project. */
export interface PartyMatch {
  id: string
  legalName: string | null
  tradingName: string | null
  kycStatus: string | null
  status: string | null
}

/**
 * `search_unavailable` is its own reason and never collapses into `none`: it is the
 * flag-gated /search endpoint answering 404, i.e. the capability is absent. Everything
 * else is the ordinary BFF failure vocabulary.
 */
export type PartyLookupFailure = BffFailure | 'search_unavailable'

export type PartyResolution =
  /** Nothing typed yet — not a result, not a failure. */
  | { status: 'idle' }
  /** Typed, but shorter than party-service will accept; asking would return a vacuous empty page. */
  | { status: 'too_short' }
  | { status: 'ok'; mode: PartyLookupMode; matches: PartyMatch[] }
  /** The lookup ran and the bank genuinely holds no such party. */
  | { status: 'none'; mode: PartyLookupMode }
  /** The lookup did not run to a conclusion. Distinct from `none` on purpose. */
  | { status: 'failed'; mode: PartyLookupMode; reason: PartyLookupFailure }

export function isUuid(value: string): boolean {
  return UUID_RE.test(value.trim())
}

function toMatch(raw: unknown): PartyMatch | null {
  if (!raw || typeof raw !== 'object') return null
  const o = raw as Record<string, unknown>
  const id = typeof o.id === 'string' ? o.id : typeof o.partyId === 'string' ? o.partyId : null
  if (!id) return null
  const str = (v: unknown): string | null => (typeof v === 'string' && v.length > 0 ? v : null)
  return {
    id,
    legalName: str(o.legalName) ?? str(o.name),
    tradingName: str(o.tradingName),
    kycStatus: str(o.kycStatus),
    status: str(o.status),
  }
}

function matchesFrom(body: unknown): PartyMatch[] {
  const rows = Array.isArray(body)
    ? body
    : Array.isArray((body as { data?: unknown })?.data)
      ? ((body as { data: unknown[] }).data)
      : Array.isArray((body as { items?: unknown })?.items)
        ? ((body as { items: unknown[] }).items)
        : []
  return rows.map(toMatch).filter((m): m is PartyMatch => m !== null)
}

type Fetcher = typeof fetch

/**
 * Resolve `query` to parties. Never throws: a transport error is a `failed` resolution,
 * because a thrown promise upstream is how a failed lookup ends up rendered as "nothing found".
 */
export async function resolveParty(query: string, fetcher: Fetcher = fetch): Promise<PartyResolution> {
  const q = query.trim()
  if (q.length === 0) return { status: 'idle' }

  const mode: PartyLookupMode = isUuid(q) ? 'uuid' : 'term'
  if (mode === 'term' && q.length < MIN_TERM_LENGTH) return { status: 'too_short' }

  const url = mode === 'uuid'
    ? svcUrl(PARTY_SERVICE, `/api/v1/parties/${encodeURIComponent(q)}`)
    : svcUrl(PARTY_SERVICE, '/api/v1/parties/search', { q, limit: '20' })

  let res: Response
  try {
    res = await fetcher(url, { signal: AbortSignal.timeout(6000), cache: 'no-store' })
  } catch {
    return { status: 'failed', mode, reason: 'unreachable' }
  }

  if (!res.ok) {
    const kind = await classifyBffFailure(res)
    // A genuine 404 means opposite things on the two endpoints, so it must not share a state.
    if (kind === 'not_found') {
      return mode === 'uuid'
        ? { status: 'none', mode }
        : { status: 'failed', mode, reason: 'search_unavailable' }
    }
    return { status: 'failed', mode, reason: kind }
  }

  let body: unknown
  try {
    body = await res.json()
  } catch {
    return { status: 'failed', mode, reason: 'error' }
  }

  const matches = mode === 'uuid'
    ? [toMatch(body)].filter((m): m is PartyMatch => m !== null)
    : matchesFrom(body)

  return matches.length > 0 ? { status: 'ok', mode, matches } : { status: 'none', mode }
}

/** Human label for a match, without inventing one when party-service minimised it away. */
export function partyLabel(m: PartyMatch): string {
  return m.tradingName ?? m.legalName ?? m.id
}
