// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Lifecycle transitions for the campaign studio (ADR-0221 D2).
//
// The action is taken from a closed list, never from the request path or an arbitrary body field:
// forwarding a caller-supplied segment into the service URL would let this route reach any campaign
// endpoint, including ones this console has no business calling.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const ACTIONS = ['submit', 'activate', 'pause', 'resume', 'close', 'enrol'] as const
type Action = (typeof ACTIONS)[number]

function isAction(value: unknown): value is Action {
  return typeof value === 'string' && (ACTIONS as readonly string[]).includes(value)
}

export async function POST(req: Request, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const { id } = await ctx.params
  const body = await req.json().catch(() => ({}))
  if (!isAction(body?.action)) {
    return NextResponse.json({ error: 'unknown action' }, { status: 400 })
  }

  // No body is forwarded. `activate` in particular takes its approver from the service's own view
  // of the authenticated caller — sending one from here would reintroduce the maker/checker hole
  // where the check compares a value the caller supplied on both sides (#3051).
  const path = `/api/v1/campaigns/${encodeURIComponent(id)}/${body.action}`
  try {
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, path), {
      method: 'POST',
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(8000),
      cache: 'no-store',
    })
    const text = await res.text()
    const payload = text ? JSON.parse(text) : {}
    if (!res.ok) {
      // A refused transition is reported with its reason. The service answers 409 with the domain
      // invariant that blocked it ("the approver must differ from the creator"), and that sentence
      // is the entire value of the response — collapsing it to "failed" makes a four-eyes rejection
      // indistinguishable from an outage.
      return NextResponse.json(
        { state: res.status === 401 || res.status === 403 ? 'forbidden' : 'rejected', ...payload },
        { status: 200 },
      )
    }
    return NextResponse.json({ state: 'ok', campaign: payload })
  } catch {
    return NextResponse.json({ state: 'unreachable' }, { status: 200 })
  }
}
