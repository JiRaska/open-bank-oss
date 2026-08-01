// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Paging and filtering the send log on its own route, separate from the campaign detail bundle.
// The bundle exists because a campaign, its enrolments and its send log are only meaningful
// together — but turning a page is not a reason to re-read the other two.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const EMPTY = { items: [], total: 0, page: 0, size: 0 }

export async function GET(req: Request, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const { id } = await ctx.params
  const incoming = new URL(req.url).searchParams

  // Forward only the parameters this endpoint defines. Passing the query string through wholesale
  // would let anything the browser appends reach the service unexamined.
  const qs = new URLSearchParams()
  for (const key of ['outcome', 'page', 'size']) {
    const value = incoming.get(key)
    if (value) qs.set(key, value)
  }

  const path = `/api/v1/campaigns/${encodeURIComponent(id)}/sends?${qs.toString()}`
  try {
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, path), {
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(6000),
      cache: 'no-store',
    })
    if (!res.ok) {
      // A refused or failed read must never arrive as an empty page with state 'ok': an empty send
      // log renders as "nothing was suppressed", which is the exact misreading this screen exists
      // to prevent.
      return NextResponse.json(
        {
          ...EMPTY,
          state:
            res.status === 401 || res.status === 403
              ? 'unauthorized'
              : res.status === 400
                ? 'bad_request'
                : res.status === 404
                  ? 'not_deployed'
                  : 'unreachable',
        },
        { status: 200 },
      )
    }
    return NextResponse.json({ ...(await res.json()), state: 'ok' })
  } catch {
    return NextResponse.json({ ...EMPTY, state: 'unreachable' }, { status: 200 })
  }
}
