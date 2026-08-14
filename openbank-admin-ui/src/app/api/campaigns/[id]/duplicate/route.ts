// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Reuse is deliberately a dedicated route, not an action in `[id]/actions`: lifecycle actions
// change the source campaign, while this endpoint produces a new maker-owned DRAFT and must never
// share a state transition's closed action list or error semantics.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

export async function POST(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const { id } = await ctx.params
  try {
    const response = await fetch(
      serverSvcUrl('campaign-service', 'campaign', 8128, `/api/v1/campaigns/${encodeURIComponent(id)}/duplicate`),
      {
        method: 'POST',
        headers: { authorization: `Bearer ${session.user.accessToken}` },
        signal: AbortSignal.timeout(8000),
        cache: 'no-store',
      },
    )
    const text = await response.text()
    const payload = text ? JSON.parse(text) : {}
    return NextResponse.json(
      response.ok
        ? { state: 'ok', campaign: payload }
        : { state: response.status === 401 || response.status === 403 ? 'forbidden' : 'rejected', ...payload },
      { status: 200 },
    )
  } catch {
    return NextResponse.json({ state: 'unreachable' }, { status: 200 })
  }
}
