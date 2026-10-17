// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ state: 'unauthorized', runs: [] }, { status: 401 })
  const { id } = await ctx.params
  try {
    const response = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, `/api/v1/campaigns/${encodeURIComponent(id)}/bulk-runs`), {
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!response.ok) return NextResponse.json({ state: response.status === 403 ? 'unauthorized' : 'unreachable', runs: [] })
    return NextResponse.json({ state: 'ok', runs: await response.json() })
  } catch {
    return NextResponse.json({ state: 'unreachable', runs: [] })
  }
}
