// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

/** The reviewed cross-channel catalogue is an upstream contract, never a second client-side list. */
export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  try {
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, '/api/v1/campaigns/templates'), {
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!res.ok) {
      return NextResponse.json({ items: [], state: res.status === 401 || res.status === 403 ? 'unauthorized' : 'unreachable' })
    }
    return NextResponse.json({ items: await res.json(), state: 'ok' })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' })
  }
}
