// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Campaign detail for the read-only console (#2895): the campaign, its enrolments, and its send
// log in one response, because the three are only meaningful together — an enrolment says a party
// is in the campaign, and only the send log says whether anything reached them and why not.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { svcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

type Part = { data: unknown; state: 'ok' | 'unauthorized' | 'unreachable' }

async function read(headers: HeadersInit, path: string, fallback: unknown): Promise<Part> {
  try {
    const res = await fetch(svcUrl('campaign-service', path), {
      headers,
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!res.ok) {
      return {
        data: fallback,
        state: res.status === 401 || res.status === 403 ? 'unauthorized' : 'unreachable',
      }
    }
    return { data: await res.json(), state: 'ok' }
  } catch {
    return { data: fallback, state: 'unreachable' }
  }
}

export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const { id } = await ctx.params
  const headers = { authorization: `Bearer ${session.user.accessToken}` }

  const [campaign, enrolments, sends] = await Promise.all([
    read(headers, `/api/v1/campaigns/${encodeURIComponent(id)}`, null),
    read(headers, `/api/v1/campaigns/${encodeURIComponent(id)}/enrolments`, []),
    read(headers, `/api/v1/campaigns/${encodeURIComponent(id)}/sends`, []),
  ])

  return NextResponse.json({
    campaign: campaign.data,
    enrolments: enrolments.data,
    sends: sends.data,
    // Per-part state travels to the client: the send log is the part most likely to be
    // restricted, and an empty send log rendered as "nothing was suppressed" would be the
    // exact misreading this screen exists to prevent.
    sources: { campaign: campaign.state, enrolments: enrolments.state, sends: sends.state },
  })
}
