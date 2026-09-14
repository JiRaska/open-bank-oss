// SPDX-License-Identifier: Apache-2.0

import { parseLiveCustomerFacts, type LiveCustomerFacts } from '@/lib/context/customerGraph'

const inflight = new Map<string, Promise<LiveCustomerFacts>>()

/** Shares one bounded BFF read across the graph and sibling Customer 360 panels mounted together. */
export function loadCustomerGraphFacts(partyId: string): Promise<LiveCustomerFacts> {
  const existing = inflight.get(partyId)
  if (existing) return existing
  const request = fetch(`/api/customer-360/${encodeURIComponent(partyId)}/graph`, {
    cache: 'no-store', signal: AbortSignal.timeout(5_000),
  }).then(async response => {
    if (!response.ok) throw new Error(String(response.status))
    return parseLiveCustomerFacts(await response.json())
  }).finally(() => inflight.delete(partyId))
  inflight.set(partyId, request)
  return request
}
