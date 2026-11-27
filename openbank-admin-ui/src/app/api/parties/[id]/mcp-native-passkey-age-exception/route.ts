// SPDX-License-Identifier: Apache-2.0

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import { serverSvcUrl } from '@/lib/services/bff'

type RouteContext = { params: Promise<{ id: string }> }
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

async function relay(req: Request, { params }: RouteContext, method: 'GET' | 'PUT' | 'DELETE') {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  if (!hasPermission(session.user.roles ?? [], 'mcp-native-passkey-age-exception:manage')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }

  if (process.env.OPENBANK_MCP_SANDBOX_PASSKEY_AGE_EXCEPTIONS !== 'true') {
    return NextResponse.json({ error: 'sandbox_policy_disabled' }, { status: 403 })
  }

  const { id } = await params
  if (!UUID.test(id)) return NextResponse.json({ error: 'invalid_id' }, { status: 400 })

  let body: string | undefined
  if (method === 'PUT') {
    const input = await req.json().catch(() => null)
    if (
      !Number.isInteger(input?.minimumAgeMinutes) || input.minimumAgeMinutes < 1 ||
      input.minimumAgeMinutes >= 72 * 60 || typeof input.expiresAt !== 'string' ||
      !Number.isFinite(Date.parse(input.expiresAt)) || typeof input.reason !== 'string' ||
      !input.reason.trim() || input.reason.trim().length > 240
    ) {
      return NextResponse.json({ error: 'invalid_policy' }, { status: 400 })
    }
    body = JSON.stringify({
      minimumAgeMinutes: input.minimumAgeMinutes,
      expiresAt: input.expiresAt,
      reason: input.reason.trim(),
    })
  }

  try {
    const upstream = await fetch(serverSvcUrl(
      'party-service', 'party', 8111, `/api/v1/parties/${id}/mcp-native-passkey-age-exception`,
    ), {
      method,
      body,
      headers: {
        authorization: `Bearer ${session.user.accessToken}`,
        ...(body ? { 'content-type': 'application/json' } : {}),
      },
      cache: 'no-store',
      signal: AbortSignal.timeout(5000),
    })
    if (upstream.status === 204) return new NextResponse(null, { status: 204 })
    if (!upstream.ok) return NextResponse.json({ error: 'policy_request_failed' }, { status: upstream.status })

    const policy = await upstream.json()
    if (
      policy?.partyId !== id || !Number.isInteger(policy.minimumAgeMinutes) ||
      typeof policy.expiresAt !== 'string' || typeof policy.reason !== 'string'
    ) {
      return NextResponse.json({ error: 'invalid_policy_response' }, { status: 502 })
    }
    return NextResponse.json({
      partyId: policy.partyId,
      minimumAgeMinutes: policy.minimumAgeMinutes,
      expiresAt: policy.expiresAt,
      reason: policy.reason,
    })
  } catch {
    return NextResponse.json({ error: 'party_unavailable' }, { status: 502 })
  }
}

export async function GET(req: Request, context: RouteContext) { return relay(req, context, 'GET') }
export async function PUT(req: Request, context: RouteContext) { return relay(req, context, 'PUT') }
export async function DELETE(req: Request, context: RouteContext) { return relay(req, context, 'DELETE') }
