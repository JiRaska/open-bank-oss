// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0228 D2: the entity-resolution facade. One BFF route fans a backoffice query out to the
// domain search providers (party business keys today; account by IBAN when the term is
// IBAN-shaped) and returns typed, UI-route-aware refs so the ⌘K palette (D3) can deep-link
// straight to the entity. The query contract on the providers does not change — the facade
// inherits each provider's guardrails (min-term, escaping, clamp) and simply merges.
//
// Phase 1 deliberately resolves only direct matches; chained resolution (party → its accounts)
// and the search-audit event (ADR-0226 channel=ui) are follow-ups noted in the ADR.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { svcUrl } from '@/lib/services/bff'
import { hasIbanShape, isValidIban, normalizeIban } from '@/lib/validation/iban'

export const dynamic = 'force-dynamic'

const MIN_TERM = 2
const MAX_RESULTS = 10

type EntityRef = {
  type: 'party' | 'account'
  id: string
  label: string
  sublabel?: string
  route: string
}

type PartySearchPage = {
  data?: Array<{ id: string; legalName: string; partyType?: string; status?: string }>
}

type AccountView = {
  id?: string
  accountNumber?: string
  currencyCode?: string
  status?: string
}

async function resolveParties(q: string, headers: HeadersInit): Promise<EntityRef[]> {
  const res = await fetch(svcUrl('party-service', '/api/v1/parties/search', { q, limit: String(MAX_RESULTS) }), {
    headers,
    signal: AbortSignal.timeout(4000),
    cache: 'no-store',
  })
  if (!res.ok) return []
  const page = (await res.json()) as PartySearchPage
  return (page.data ?? []).map(p => ({
    type: 'party' as const,
    id: p.id,
    label: p.legalName,
    sublabel: [p.partyType, p.status].filter(Boolean).join(' · ') || undefined,
    route: `/parties/${p.id}`,
  }))
}

async function resolveAccountByIban(q: string, headers: HeadersInit): Promise<EntityRef[]> {
  if (!hasIbanShape(q) || !isValidIban(q)) return []
  const res = await fetch(svcUrl('account-service', `/api/v1/accounts/iban/${normalizeIban(q)}`), {
    headers,
    signal: AbortSignal.timeout(4000),
    cache: 'no-store',
  })
  if (!res.ok) return []
  const acc = (await res.json()) as AccountView
  if (!acc.id) return []
  return [{
    type: 'account',
    id: acc.id,
    label: acc.accountNumber ?? normalizeIban(q),
    sublabel: [acc.currencyCode, acc.status].filter(Boolean).join(' · ') || undefined,
    route: `/accounts/${acc.id}`,
  }]
}

export async function GET(req: NextRequest) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const q = (req.nextUrl.searchParams.get('q') ?? '').trim()
  if (q.length < MIN_TERM) {
    return NextResponse.json({ results: [] satisfies EntityRef[] })
  }
  const headers = { authorization: `Bearer ${session.user.accessToken}` }
  const [parties, accounts] = await Promise.all([
    resolveParties(q, headers).catch(() => []),
    resolveAccountByIban(q, headers).catch(() => []),
  ])
  return NextResponse.json({ results: [...parties, ...accounts].slice(0, MAX_RESULTS) })
}
