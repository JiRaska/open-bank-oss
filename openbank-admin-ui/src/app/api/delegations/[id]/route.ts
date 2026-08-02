// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 + ADR-0232: single-grant detail read for the console's grant page.
//
// GET only. delegation-service exposes DELETE /{id} (revoke) on this same path, and this route
// deliberately does not implement it — see src/test/delegations-no-mutation.guard.test.ts for
// the enforced invariant and the PR body for why the mutation half is not shippable yet.
//
// See ../party/[partyId]/route.ts for why X-Customer-Party-Id is not sent.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const TIMEOUT_MS = 4000

export async function GET(
  _req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }

  const { id } = await params
  if (!UUID_RE.test(id)) {
    return NextResponse.json({ error: 'invalid_delegation_id' }, { status: 400 })
  }

  try {
    const res = await fetch(
      serverSvcUrl('delegation-service', 'delegation', 8126, `/api/v1/delegations/${id}`),
      {
        headers: { authorization: `Bearer ${session.user.accessToken}` },
        signal: AbortSignal.timeout(TIMEOUT_MS),
        cache: 'no-store',
      },
    )
    if (res.status === 404) {
      return NextResponse.json({ error: 'not_found' }, { status: 404 })
    }
    if (!res.ok) {
      // Never relay the upstream body — it may carry internal detail, and the console only
      // needs to tell "refused" apart from "broken" to pick its empty state.
      const status = res.status === 401 || res.status === 403 ? 403 : 502
      return NextResponse.json({ error: status === 403 ? 'forbidden' : 'upstream_error' }, { status })
    }
    return NextResponse.json(await res.json())
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
