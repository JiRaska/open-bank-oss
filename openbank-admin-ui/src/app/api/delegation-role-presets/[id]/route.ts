// SPDX-License-Identifier: Apache-2.0
import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
async function relay(request: Request, id: string, method: 'PUT' | 'DELETE') {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  if (!UUID_RE.test(id)) return NextResponse.json({ error: 'invalid_preset_id' }, { status: 400 })
  try {
    const response = await fetch(serverSvcUrl('delegation-service', 'delegation', 8126, `/api/v1/delegation-role-presets/${id}`), {
      method, headers: { authorization: `Bearer ${session.user.accessToken}`, 'content-type': 'application/json' },
      body: method === 'PUT' ? await request.text() : undefined, cache: 'no-store', signal: AbortSignal.timeout(5000),
    })
    if (!response.ok) return NextResponse.json({ error: response.status === 403 ? 'forbidden' : response.status === 404 ? 'not_found' : 'upstream_error' }, { status: response.status })
    return new NextResponse(response.status === 204 ? null : await response.text(), { status: response.status, headers: response.status === 204 ? {} : { 'content-type': 'application/json' } })
  } catch { return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 }) }
}
export async function PUT(request: Request, { params }: { params: Promise<{ id: string }> }) { return relay(request, (await params).id, 'PUT') }
export async function DELETE(request: Request, { params }: { params: Promise<{ id: string }> }) { return relay(request, (await params).id, 'DELETE') }
