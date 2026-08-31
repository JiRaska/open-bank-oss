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

  return NextResponse.json({
    partyId,
    accounts: list(accounts.data),
    cards: list(cards.data),
    grants: list(grants.data),
    presets: list(presets.data),
    sources: { accounts: accounts.state, cards: cards.state, grants: grants.state, presets: presets.state },
  }, { headers: { 'cache-control': 'no-store' } })
}
