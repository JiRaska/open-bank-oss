// SPDX-License-Identifier: Apache-2.0

import { parseLiveCustomerFacts, type LiveCustomerFacts } from '@/lib/context/customerGraph'

const inflight = new Map<string, Promise<LiveCustomerFacts>>()
let selected: { partyId: string; facts: Promise<LiveCustomerFacts> } | null = null

export class GraphAccessDeniedError extends Error {
  constructor() { super('Graph access denied') }
}

/** Starts and retains one live snapshot for the party currently selected on Customer 360. */
export function selectCustomerGraphFacts(partyId: string): Promise<LiveCustomerFacts> {
  if (selected) inflight.delete(selected.partyId)
  selected = null
  const facts = requestCustomerGraphFacts(partyId)
  selected = { partyId, facts }
  void facts.catch(() => {
    if (selected?.facts === facts) selected = null
  })
  return facts
}

export function clearSelectedCustomerGraphFacts(partyId: string): void {
  if (selected?.partyId === partyId) selected = null
  inflight.delete(partyId)
}

/** Shares one bounded BFF read across the graph and sibling Customer 360 panels mounted together. */
export function loadCustomerGraphFacts(partyId: string): Promise<LiveCustomerFacts> {
  if (selected?.partyId === partyId) return selected.facts
  return requestCustomerGraphFacts(partyId)
}

function requestCustomerGraphFacts(partyId: string): Promise<LiveCustomerFacts> {
  const existing = inflight.get(partyId)
  if (existing) return existing
  const request = fetch(`/api/customer-360/${encodeURIComponent(partyId)}/graph`, {
    cache: 'no-store', signal: AbortSignal.timeout(5_000),
  }).then(async response => {
    if (response.status === 401 || response.status === 403) throw new GraphAccessDeniedError()
    if (!response.ok) throw new Error(String(response.status))
    return parseLiveCustomerFacts(await response.json())
  }).finally(() => {
    if (inflight.get(partyId) === request) inflight.delete(partyId)
  })
  inflight.set(partyId, request)
  return request
}
