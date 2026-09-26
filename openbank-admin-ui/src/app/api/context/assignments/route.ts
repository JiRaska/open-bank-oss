// SPDX-License-Identifier: Apache-2.0

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { AUTHORITY_UUID } from '@/lib/context/authorityHistory'
import { contextHeaders, contextServiceUrl } from '@/lib/context/server'
import { readBoundedContextJson } from '@/lib/context/boundedJson'

export const dynamic = 'force-dynamic'

async function adminToken(): Promise<string | null> {
  const session = await auth()
  return session?.user?.roles?.includes('ROLE_ADMIN') ? session.user.accessToken : null
}

export async function GET() {
  const token = await adminToken()
  if (!token) return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  return relay(contextServiceUrl('/api/v1/context/assignment-proposals/assignments?limit=100'), token)
}

export async function POST(req: NextRequest) {
  const token = await adminToken()
  if (!token) return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  const body = await readBoundedContextJson(req, 16 * 1024).catch(() => null)
  if (!validProposal(body)) return NextResponse.json({ error: 'invalid_assignment' }, { status: 400 })
  return relay(contextServiceUrl('/api/v1/context/assignment-proposals'), token, 'POST', body)
}

async function relay(url: string, token: string, method = 'GET', body?: unknown) {
  try {
    const upstream = await fetch(url, {
      method, cache: 'no-store', headers: contextHeaders(token),
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(5_000),
    })
    const payload = upstream.status === 204 ? null : await readBoundedContextJson(upstream, 256 * 1024).catch(() => ({ error: 'invalid_response' }))
    return NextResponse.json(payload, { status: upstream.status, headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}

function validProposal(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== 'object') return false
  const body = value as Record<string, unknown>
  const text = (key: string, max: number) => typeof body[key] === 'string' && (body[key] as string).trim().length > 0 && (body[key] as string).length <= max
  const rootValid = body.purpose === 'AUTHORIZATION_REVIEW'
    ? typeof body.rootRef === 'string' && /^delegation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(body.rootRef)
    : body.purpose === 'AML_INVESTIGATION'
      ? typeof body.caseId === 'string' && AUTHORITY_UUID.test(body.caseId) &&
        typeof body.rootRef === 'string' && body.rootRef.startsWith('aml-case:') &&
        AUTHORITY_UUID.test(body.rootRef.slice(9)) && body.rootRef.slice(9).toLowerCase() === body.caseId.toLowerCase()
      : body.purpose === 'INCIDENT_IMPACT'
        ? typeof body.rootRef === 'string' && body.rootRef.startsWith('incident:') && AUTHORITY_UUID.test(body.rootRef.slice(9))
        : body.purpose === 'PAYMENT_COMPLAINT'
          ? typeof body.rootRef === 'string' && /^complaint:[A-Za-z0-9._:-]{1,200}$/.test(body.rootRef)
          : false
  return rootValid && text('principalId', 200) && text('caseId', 200) && text('purpose', 80) &&
    typeof body.validTo === 'string' && Number.isFinite(Date.parse(body.validTo))
}
