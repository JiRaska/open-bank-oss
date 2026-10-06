// SPDX-License-Identifier: Apache-2.0

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { contextHeaders, contextServiceUrl } from '@/lib/context/server'
import { readBoundedContextJson } from '@/lib/context/boundedJson'

export const dynamic = 'force-dynamic'

export async function GET() {
  const session = await auth()
  if (!session?.user?.roles?.includes('ROLE_ADMIN') || !session.user.accessToken) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }
  try {
    const upstream = await fetch(contextServiceUrl('/api/v1/context/assignment-proposals?limit=100'), {
      cache: 'no-store', headers: contextHeaders(session.user.accessToken), signal: AbortSignal.timeout(5_000),
    })
    return NextResponse.json(await readBoundedContextJson(upstream, 256 * 1024), { status: upstream.status, headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
