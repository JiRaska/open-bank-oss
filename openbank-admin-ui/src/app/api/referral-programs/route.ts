// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  try {
    const response = await fetch(serverSvcUrl('referral-service', 'campaign', 8155, '/api/v1/referrals/programs'), {
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!response.ok) {
      const state = response.status === 401 || response.status === 403
        ? 'unauthorized'
        : response.status === 404
          ? 'not_deployed'
          : 'unreachable'
      return NextResponse.json({ items: [], state })
    }
    return NextResponse.json({ items: await response.json(), state: 'ok' })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' })
  }
}
