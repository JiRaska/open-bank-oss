// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  try {
    const response = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, '/api/v1/audiences'), {
      headers: { authorization: `Bearer ${session.user.accessToken}` }, signal: AbortSignal.timeout(4000), cache: 'no-store',
    })
    if (!response.ok) return NextResponse.json({ items: [], state: response.status === 401 || response.status === 403 ? 'unauthorized' : response.status === 404 ? 'not_deployed' : 'unreachable' })
    return NextResponse.json({ items: await response.json(), state: 'ok' })
  } catch { return NextResponse.json({ items: [], state: 'unreachable' }) }
}

export async function POST(request: NextRequest) {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  const response = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, '/api/v1/audiences'), {
    method: 'POST', headers: { authorization: `Bearer ${session.user.accessToken}`, 'content-type': 'application/json' }, body: await request.text(), signal: AbortSignal.timeout(5000), cache: 'no-store',
  })
  return NextResponse.json(await response.json(), { status: response.status })
}
