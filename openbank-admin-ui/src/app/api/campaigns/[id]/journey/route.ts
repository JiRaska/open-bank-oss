// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Per-step funnel for the journey view. Every number is a SQL aggregate on the service side; this
// route adds nothing and computes nothing, so the picture cannot disagree with the send log.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const { id } = await ctx.params
  const path = `/api/v1/campaigns/${encodeURIComponent(id)}/journey`
  try {
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, path), {
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(6000),
      cache: 'no-store',
    })
    if (!res.ok) {
      // An unreadable funnel must not arrive as an empty one: "nobody was reached" is a business
      // conclusion a marketer would act on, and a 403 or a timeout is not that conclusion.
      return NextResponse.json(
        {
          steps: [],
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
    return NextResponse.json({ steps: await res.json(), state: 'ok' })
  } catch {
    return NextResponse.json({ steps: [], state: 'unreachable' }, { status: 200 })
  }
}
