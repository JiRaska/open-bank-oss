// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 (hybrid consoles) + ADR-0232 (delegated access): the read half of the backoffice
// delegation console. One BFF call answers "what has this party shared, and what has been
// shared with them" by fanning out to delegation-service's two list endpoints.
//
// The two directions are reported SEPARATELY (`sources`), never merged into one silent list:
// "this party has no grants" and "the grantor read was refused" look identical in a flat array,
// and on a rights-management screen the wrong one of those is the dangerous one — an operator
// investigating a fraud report would conclude nobody holds rights over the account.
//
// X-Customer-Party-Id is deliberately NOT sent. That header is customer-edge's IDOR guard for
// customer-scoped calls; delegation-service refuses any operation whose claimed party differs
// from it. An operator console is gated by role + OPA instead (the header's own contract says
// "absent on an operator/back-office call"), so stamping it here would scope every backoffice
// read to whichever party we guessed and break the console's entire purpose.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const TIMEOUT_MS = 4000

export type DirectionState = 'ok' | 'forbidden' | 'unavailable'

type DirectionResult = { grants: unknown[]; state: DirectionState }

function stateFor(status: number): DirectionState {
  return status === 401 || status === 403 ? 'forbidden' : 'unavailable'
}

async function listGrants(
  direction: 'grantor' | 'grantee',
  partyId: string,
  headers: HeadersInit,
): Promise<DirectionResult> {
  const res = await fetch(
    serverSvcUrl('delegation-service', 'delegation', 8126, `/api/v1/delegations/${direction}/${partyId}`),
    { headers, signal: AbortSignal.timeout(TIMEOUT_MS), cache: 'no-store' },
  )
  if (!res.ok) return { grants: [], state: stateFor(res.status) }
  const body = await res.json()
  return { grants: Array.isArray(body) ? body : [], state: 'ok' }
}

export async function GET(
  _req: Request,
  { params }: { params: Promise<{ partyId: string }> },
) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }

  // Validate before any upstream call: a malformed id would otherwise be relayed to
  // delegation-service, whose 404 the console cannot distinguish from "no such grant".
  const { partyId } = await params
  if (!UUID_RE.test(partyId)) {
    return NextResponse.json({ error: 'invalid_party_id' }, { status: 400 })
  }

  const headers = { authorization: `Bearer ${session.user.accessToken}` }
  const unavailable: DirectionResult = { grants: [], state: 'unavailable' }
  const [granted, received] = await Promise.all([
    listGrants('grantor', partyId, headers).catch(() => unavailable),
    listGrants('grantee', partyId, headers).catch(() => unavailable),
  ])

  return NextResponse.json({
    partyId,
    granted: granted.grants,
    received: received.grants,
    sources: { granted: granted.state, received: received.state },
  })
}
