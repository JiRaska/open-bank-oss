// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export async function POST(_req: Request, ctx: { params: Promise<{ id: string; runId: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  const { id, runId } = await ctx.params
  const path = `/api/v1/campaigns/${encodeURIComponent(id)}/bulk-runs/${encodeURIComponent(runId)}/resume`
  try {
    const response = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, path), {
      method: 'POST',
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(8000),
      cache: 'no-store',
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) {
      return NextResponse.json({ state: response.status === 403 ? 'forbidden' : 'rejected', ...payload })
    }
    return NextResponse.json({ state: 'ok', run: payload })
  } catch {
    return NextResponse.json({ state: 'unreachable' })
  }
}
