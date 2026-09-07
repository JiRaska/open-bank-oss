// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One party's Lístek ledger, for the console's party view (ADR-0282 D8).
//
// This route returns exactly what the customer's own app surface shows — the same balance, the
// same history, the same expiry. That symmetry is the point of D8's reciprocal transparency, so
// this route deliberately has no operator-only enrichment to add to it.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { requireApiPermission } from '@/lib/auth/api-permission'
import { serverSvcUrl } from '@/lib/services/bff'
import type { LoyaltyState } from '../../route'

export const dynamic = 'force-dynamic'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export interface LeafLedgerRow {
  id: string
  type: 'EARN' | 'BURN' | 'EXPIRE' | 'REVERSE'
  leaves: number
  remainingLeaves: number
  earnSourceId: string | null
  benefitId: string | null
  ruleVersion: string
  occurredAt: string
  expiresAt: string | null
}

export interface LoyaltyPartyResponse {
  state: LoyaltyState
  partyId: string
  balance: number
  earnedThisYear: number
  earnedTotal: number
  nextExpiry: string | null
  history: LeafLedgerRow[]
}

export async function GET(_request: NextRequest, context: { params: Promise<{ partyId: string }> }) {
  const permitted = await requireApiPermission('loyalty:view')
  if (!permitted.ok) {
    return NextResponse.json({ error: permitted.error }, { status: permitted.status })
  }
  const { partyId } = await context.params
  if (!UUID_RE.test(partyId)) {
    return NextResponse.json({ error: 'partyId must be a UUID' }, { status: 400 })
  }
  const session = await auth()
  const token = session?.user?.accessToken
  if (!token) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })

  const empty = { partyId, balance: 0, earnedThisYear: 0, earnedTotal: 0, nextExpiry: null, history: [] }
  try {
    const response = await fetch(
      serverSvcUrl('loyalty-service', 'loyalty', 8157, `/api/v1/loyalty/parties/${partyId}`),
      {
        headers: { authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(4000),
        cache: 'no-store',
      },
    )
    if (!response.ok) {
      const state: LoyaltyState = response.status === 401 || response.status === 403
        ? 'unauthorized'
        : response.status === 404 ? 'not_deployed' : 'unreachable'
      return NextResponse.json({ state, ...empty })
    }
    const body = await response.json() as Omit<LoyaltyPartyResponse, 'state'>
    return NextResponse.json({ state: 'ok', ...empty, ...body })
  } catch {
    return NextResponse.json({ state: 'unreachable', ...empty })
  }
}
