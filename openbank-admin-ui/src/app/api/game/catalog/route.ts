// SPDX-License-Identifier: Apache-2.0
import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { requireApiPermission } from '@/lib/auth/api-permission'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

async function relay(method: 'GET' | 'PUT', request?: NextRequest) {
  const permission = await requireApiPermission(method === 'GET' ? 'game:view' : 'game:manage')
  if (!permission.ok) return NextResponse.json({ error: permission.error }, { status: permission.status })
  const session = await auth()
  const token = session?.user?.accessToken
  if (!token) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  let body: string | undefined
  if (method === 'PUT') {
    if (Number(request?.headers.get('content-length')) > 65536) return NextResponse.json({ error: 'too_large' }, { status: 413 })
    body = await request?.text()
    if (!body || body.length > 65536) return NextResponse.json({ error: 'invalid_catalogue' }, { status: 400 })
    try { JSON.parse(body) } catch { return NextResponse.json({ error: 'invalid_json' }, { status: 400 }) }
  }
  try {
    const upstream = await fetch(serverSvcUrl('engagement-service', 'engagement', 8153, '/api/v1/game/admin/catalog'), {
      method, body,
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      cache: 'no-store', signal: AbortSignal.timeout(8000),
    })
    if (!upstream.ok) {
      const status = [400, 401, 403, 409].includes(upstream.status) ? upstream.status : 503
      return NextResponse.json({ error: status === 409 ? 'catalogue_changed' : 'catalogue_unavailable' }, { status })
    }
    return NextResponse.json(await upstream.json(), { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json({ error: 'catalogue_unavailable' }, { status: 503 })
  }
}

export async function GET() { return relay('GET') }
export async function PUT(request: NextRequest) { return relay('PUT', request) }
