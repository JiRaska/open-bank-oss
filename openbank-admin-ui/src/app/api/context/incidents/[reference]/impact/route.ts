// SPDX-License-Identifier: Apache-2.0

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import { contextServiceUrl } from '@/lib/context/server'
import { parseIncidentImpact } from '@/lib/context/incidentImpact'

export const dynamic = 'force-dynamic'
const SAFE_VALUE = /^[A-Za-z0-9._:-]{1,200}$/
const PURPOSE = /^[A-Z][A-Z0-9_]{0,79}$/

export async function GET(req: NextRequest, ctx: { params: Promise<{ reference: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  if (!hasPermission(session.user.roles ?? [], 'system:view')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }
  const { reference } = await ctx.params
  const caseId = req.nextUrl.searchParams.get('caseId') ?? ''
  const purpose = req.nextUrl.searchParams.get('purpose') ?? ''
  if (!SAFE_VALUE.test(reference) || !SAFE_VALUE.test(caseId) || !PURPOSE.test(purpose)) {
    return NextResponse.json({ error: 'invalid_investigation_context' }, { status: 400 })
  }
  try {
    const upstream = await fetch(contextServiceUrl(`/api/v1/context/incidents/${encodeURIComponent(reference)}/impact`), {
      cache: 'no-store',
      headers: {
        Accept: 'application/json', Authorization: `Bearer ${session.user.accessToken}`,
        'X-Investigation-Case-Id': caseId, 'X-Investigation-Purpose': purpose,
      },
      signal: AbortSignal.timeout(5_000),
    })
    if (!upstream.ok) return NextResponse.json({ error: 'context_unavailable' }, { status: upstream.status < 504 ? upstream.status : 502 })
    const body = await upstream.json() as unknown
    let impact
    try { impact = parseIncidentImpact(body) }
    catch { return NextResponse.json({ error: 'invalid_response' }, { status: 502 }) }
    return NextResponse.json(impact, { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
