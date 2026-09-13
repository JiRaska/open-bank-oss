// SPDX-License-Identifier: Apache-2.0
import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'
const upstream = () => serverSvcUrl('delegation-service', 'delegation', 8126, '/api/v1/delegation-role-presets')

async function relay(request: Request, method: 'GET' | 'POST') {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  try {
    const response = await fetch(upstream(), {
      method, headers: { authorization: `Bearer ${session.user.accessToken}`, 'content-type': 'application/json' },
      body: method === 'POST' ? await request.text() : undefined, cache: 'no-store', signal: AbortSignal.timeout(5000),
    })
    if (!response.ok) return NextResponse.json({ error: response.status === 403 ? 'forbidden' : 'upstream_error' }, { status: response.status === 403 ? 403 : response.status })
    return new NextResponse(response.status === 204 ? null : await response.text(), { status: response.status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } })
  } catch { return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 }) }
}

export async function GET(request: Request) { return relay(request, 'GET') }
export async function POST(request: Request) { return relay(request, 'POST') }
