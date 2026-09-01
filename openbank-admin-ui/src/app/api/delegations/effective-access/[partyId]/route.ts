// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const TIMEOUT_MS = 5000
type SourceState = 'ok' | 'forbidden' | 'unavailable'
type SourceResult = { data: unknown; state: SourceState }
const MAX_RESOURCE_DETAILS = 50

const stateFor = (status: number): SourceState => status === 401 || status === 403 ? 'forbidden' : 'unavailable'

async function read(url: string, headers: HeadersInit): Promise<SourceResult> {
  try {
    const response = await fetch(url, { headers, cache: 'no-store', signal: AbortSignal.timeout(TIMEOUT_MS) })
    if (!response.ok) return { data: [], state: stateFor(response.status) }
    return { data: await response.json(), state: 'ok' }
  } catch {
    return { data: [], state: 'unavailable' }
  }
}

const list = (value: unknown): Record<string, unknown>[] => {
  if (Array.isArray(value)) return value.filter(item => item && typeof item === 'object') as Record<string, unknown>[]
  if (value && typeof value === 'object') {
    const data = (value as { data?: unknown }).data
    if (Array.isArray(data)) return data.filter(item => item && typeof item === 'object') as Record<string, unknown>[]
  }
  return []
}

const object = (value: unknown): Record<string, unknown> | undefined =>
  value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined

const resourceKey = (resourceType: unknown, resourceId: unknown) => `${String(resourceType)}:${String(resourceId)}`

const time = (value: unknown): number | null => {
  if (typeof value !== 'string' || value.length === 0) return null
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

const effectiveAt = (grant: Record<string, unknown>, now: number): boolean => {
  if (grant.status !== 'ACTIVE') return false
  const validFrom = time(grant.validFrom)
  if (validFrom === null || validFrom > now) return false
  if (grant.validTo === null || grant.validTo === undefined) return true
  const validTo = time(grant.validTo)
  return validTo !== null && now < validTo
}

const nextBoundary = (grants: Record<string, unknown>[], now: number): string | null => {
  const candidates = grants.flatMap(grant => {
    if (grant.status !== 'ACTIVE') return []
    return [time(grant.validFrom), time(grant.validTo)].filter((value): value is number => value !== null && value > now)
  })
  return candidates.length === 0 ? null : new Date(Math.min(...candidates)).toISOString()
}

export async function GET(_request: Request, { params }: { params: Promise<{ partyId: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  const { partyId } = await params
  if (!UUID_RE.test(partyId)) return NextResponse.json({ error: 'invalid_party_id' }, { status: 400 })

  const headers = { authorization: `Bearer ${session.user.accessToken}` }
  const [accounts, cards, grants, presets] = await Promise.all([
    read(serverSvcUrl('account-service', 'accounts', 8100, `/api/v1/accounts?partyId=${encodeURIComponent(partyId)}&limit=100`), headers),
    read(serverSvcUrl('card-issuance-service', 'card-issuance', 8118, `/api/v1/cards/party/${partyId}`), headers),
    read(serverSvcUrl('delegation-service', 'delegation', 8126, `/api/v1/delegations/grantee/${partyId}`), headers),
    read(serverSvcUrl('delegation-service', 'delegation', 8126, '/api/v1/delegation-role-presets'), headers),
  ])
  const grantList = list(grants.data)
  const evaluatedAt = new Date()
  const nextChangeAt = nextBoundary(grantList, evaluatedAt.getTime())
  const effectiveGrants = grantList.filter(grant => effectiveAt(grant, evaluatedAt.getTime()))
  const activeResources = [...new Map(effectiveGrants
    .filter(grant => typeof grant.resourceId === 'string' && UUID_RE.test(grant.resourceId) &&
      (grant.resourceType === 'ACCOUNT' || grant.resourceType === 'SAVINGS_GOAL' || grant.resourceType === 'CARD'))
    .map(grant => [resourceKey(grant.resourceType, grant.resourceId), grant])).values()]
  const resourcesToResolve = activeResources.slice(0, MAX_RESOURCE_DETAILS)
  const resourceDetails = await Promise.all(resourcesToResolve.map(async grant => {
    const resourceType = String(grant.resourceType)
    const resourceId = String(grant.resourceId)
    const path = resourceType === 'CARD' ? `/api/v1/cards/${resourceId}` : `/api/v1/accounts/${resourceId}`
    const service = resourceType === 'CARD'
      ? ['card-issuance-service', 'card-issuance', 8118] as const
      : ['account-service', 'accounts', 8100] as const
    const result = await read(serverSvcUrl(service[0], service[1], service[2], path), headers)
    return { key: resourceKey(resourceType, resourceId), resourceType, resourceId, state: result.state, detail: object(result.data) }
  }))
  // Detail lookups may consume most of the upstream timeout. Return the remaining server-clock
  // delay at the instant the response is assembled so the browser never extends a stale snapshot.
  const nextChangeMs = time(nextChangeAt)
  const refreshAfterMs = nextChangeMs === null ? null : Math.max(nextChangeMs - Date.now(), 0)

  return NextResponse.json({
    partyId,
    evaluatedAt: evaluatedAt.toISOString(),
    nextChangeAt,
    refreshAfterMs,
    accounts: list(accounts.data),
    cards: list(cards.data),
    // Keep non-effective rows for the explicit "needs attention" explanation, but resolve
    // concrete resource details only for grants effective at the BFF timestamp above.
    grants: grantList,
    presets: list(presets.data),
    resourceDetails,
    resourceDetailsTruncated: activeResources.length > MAX_RESOURCE_DETAILS,
    sources: { accounts: accounts.state, cards: cards.state, grants: grants.state, presets: presets.state },
  }, { headers: { 'cache-control': 'no-store' } })
}
