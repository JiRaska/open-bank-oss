// SPDX-License-Identifier: Apache-2.0

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { contextHeaders, contextServiceUrl } from '@/lib/context/server'
import { readBoundedContextJson } from '@/lib/context/boundedJson'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

async function adminToken(): Promise<string | null> {
  const session = await auth()
  return session?.user?.roles?.includes('ROLE_ADMIN') ? session.user.accessToken : null
}

export async function PATCH(req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const token = await adminToken(); const { id } = await ctx.params
  if (!token) return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  if (!UUID.test(id)) return NextResponse.json({ error: 'invalid_id' }, { status: 400 })
  const body = await readBoundedContextJson(req, 1024).catch(() => null) as { approve?: unknown } | null
  if (typeof body?.approve !== 'boolean') return NextResponse.json({ error: 'invalid_decision' }, { status: 400 })
  return relay(`/api/v1/context/assignment-proposals/${id}`, token, 'PATCH', { approve: body.approve })
}

export async function DELETE(_req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const token = await adminToken(); const { id } = await ctx.params
  if (!token) return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  if (!UUID.test(id)) return NextResponse.json({ error: 'invalid_id' }, { status: 400 })
  return relay(`/api/v1/context/assignment-proposals/assignments/${id}`, token, 'DELETE')
}

async function relay(path: string, token: string, method: string, body?: unknown) {
  try {
    const upstream = await fetch(contextServiceUrl(path), {
      method, cache: 'no-store', headers: contextHeaders(token),
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(5_000),
    })
    if (upstream.status === 204) return new NextResponse(null, { status: 204 })
    return NextResponse.json(await readBoundedContextJson(upstream, 256 * 1024), { status: upstream.status, headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
