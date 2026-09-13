// SPDX-License-Identifier: Apache-2.0
import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  try {
    const response = await fetch(serverSvcUrl('incentive-service', 'incentive', 8156, '/api/v1/incentives/offers'), {
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!response.ok) {
      return NextResponse.json({
        items: [],
        state: response.status === 401 || response.status === 403
          ? 'unauthorized'
          : response.status === 404 ? 'not_deployed' : 'unreachable',
      })
    }
    const body = await response.json() as { items?: unknown[] }
    return NextResponse.json({ items: body.items ?? [], state: 'ok' })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' })
  }
}
