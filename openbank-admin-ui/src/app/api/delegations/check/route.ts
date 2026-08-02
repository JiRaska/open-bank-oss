// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 + ADR-0232: the coverage probe. "Does any live grant let party X do capability C on
// resource R (optionally for amount A)?" — the same question account-service and card-issuance
// ask before honouring a delegated action, asked from the operator console.
//
// This is a POST that CHANGES NOTHING: delegation-service's /check is a pure evaluation over
// existing grants (DelegationCheckResult -> {granted, reason, code}), POST only because the
// query is a structured body. It is therefore not a mutation path, and the no-mutation guard
// (src/test/delegations-no-mutation.guard.test.ts) knows it by upstream path, not by HTTP verb.
//
// Why it earns its place on a read-only console: a grant list shows what EXISTS, not what the
// enforcement point CONCLUDES. Ceilings, expiry, status and capability interact, so an operator
// answering "could this delegate really have made that payment?" from the list is guessing.
// This asks the authority directly and shows its own reason code.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const TIMEOUT_MS = 4000

type CheckBody = {
  granteePartyId?: unknown
  resourceType?: unknown
  resourceId?: unknown
  capability?: unknown
  amount?: { amount?: unknown; currency?: unknown } | null
}

export async function POST(req: Request) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }

  let body: CheckBody
  try {
    body = (await req.json()) as CheckBody
  } catch {
    return NextResponse.json({ error: 'invalid_body' }, { status: 400 })
  }

  const { granteePartyId, resourceType, resourceId, capability, amount } = body
  if (
    typeof granteePartyId !== 'string' || !UUID_RE.test(granteePartyId) ||
    typeof resourceId !== 'string' || !UUID_RE.test(resourceId) ||
    typeof resourceType !== 'string' || !resourceType ||
    typeof capability !== 'string' || !capability
  ) {
    return NextResponse.json({ error: 'invalid_body' }, { status: 400 })
  }

  // Forward a re-built body, never the caller's object: an unvalidated passthrough would let a
  // console user smuggle extra fields straight into the authority's request DTO.
  const upstream: Record<string, unknown> = { granteePartyId, resourceType, resourceId, capability }
  if (amount && typeof amount.amount === 'number' && typeof amount.currency === 'string') {
    upstream.amount = { amount: amount.amount, currency: amount.currency }
  }

  try {
    const res = await fetch(
      serverSvcUrl('delegation-service', 'delegation', 8126, '/api/v1/delegations/check'),
      {
        method: 'POST',
        headers: {
          authorization: `Bearer ${session.user.accessToken}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify(upstream),
        signal: AbortSignal.timeout(TIMEOUT_MS),
        cache: 'no-store',
      },
    )
    if (!res.ok) {
      const status = res.status === 401 || res.status === 403 ? 403 : 502
      return NextResponse.json({ error: status === 403 ? 'forbidden' : 'upstream_error' }, { status })
    }
    return NextResponse.json(await res.json())
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
