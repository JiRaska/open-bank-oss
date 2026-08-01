// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Read-only segment catalogue (#2895). There is no POST on purpose: ADR-0201 D1 makes a segment a
// versioned artifact defined in code, so a new segment is a pull request, not a UI action.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const headers = { authorization: `Bearer ${session.user.accessToken}` }
  try {
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, '/api/v1/segments'), {
      headers,
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!res.ok) {
      // Same reasoning as the campaigns route: a refused read must not render as an empty
      // catalogue. "No segments exist" and "you may not see them" are different answers.
      return NextResponse.json(
        {
          items: [],
          state:
            res.status === 401 || res.status === 403
              ? 'unauthorized'
              : res.status === 404
                ? 'not_deployed'
                : 'unreachable',
        },
        { status: 200 },
      )
    }
    return NextResponse.json({ items: await res.json(), state: 'ok' })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' }, { status: 200 })
  }
}
