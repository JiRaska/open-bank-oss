// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { serverSvcUrl } from '@/lib/services/bff'

/**
 * Resolve party ids to display names for the campaign console.
 *
 * campaign-service stores only `partyId`, so every campaign screen showed `05a02ef1` — unreadable
 * for the people the screen is for. The name lives in party-service, which means a cross-service
 * lookup rather than a formatting change.
 *
 * Three properties this deliberately has:
 *
 *  - **Deduplicated.** A send log page repeats the same party across steps; resolving per row would
 *    issue the same request several times for one screen.
 *  - **Bounded.** Never more ids than one page can show, and the fan-out is capped, so a large page
 *    cannot turn one console request into a burst against party-service.
 *  - **Non-fatal.** A party that cannot be resolved — deleted, restricted, or party-service being
 *    unavailable — falls back to the short id. A campaign screen must not go blank because a name
 *    lookup failed; the id is worse to read but it is still the truth.
 *
 * Note this puts customer names on a marketing screen. That is a deliberate product decision (the
 * screen is already behind `compliance:view`), not a formatting one — the audit trail for who read
 * what stays with party-service, which is where the access is actually made.
 */

const MAX_LOOKUPS = 60

export type PartyNames = Record<string, string>

export async function resolvePartyNames(headers: HeadersInit, ids: string[]): Promise<PartyNames> {
  const unique = Array.from(new Set(ids.filter(Boolean))).slice(0, MAX_LOOKUPS)
  if (unique.length === 0) return {}

  const entries = await Promise.all(
    unique.map(async id => {
      try {
        const res = await fetch(
          serverSvcUrl('party-service', 'party', 8111, `/api/v1/parties/${encodeURIComponent(id)}`),
          { headers, signal: AbortSignal.timeout(3000), cache: 'no-store' },
        )
        if (!res.ok) return null
        const body = (await res.json()) as { legalName?: string; tradingName?: string }
        const name = body.tradingName?.trim() || body.legalName?.trim()
        return name ? ([id, name] as const) : null
      } catch {
        // Deliberately swallowed: an unresolved name degrades to the id, and one slow party must
        // not decide whether the campaign screen renders.
        return null
      }
    }),
  )

  return Object.fromEntries(entries.filter((e): e is readonly [string, string] => e !== null))
}
