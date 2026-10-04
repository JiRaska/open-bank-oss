// SPDX-License-Identifier: Apache-2.0
import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { contextServiceUrl } from '@/lib/context/server'
import { AUTHORITY_UUID, authorityTimestamp, parseAuthorityHistory } from '@/lib/context/authorityHistory'

export const dynamic = 'force-dynamic'

export async function GET(req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  if (!session.user.roles?.some(role => ['ROLE_ADMIN', 'ROLE_COMPLIANCE'].includes(role))) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }
  const { id } = await ctx.params
  const caseId = req.nextUrl.searchParams.get('caseId') ?? ''
  if (!AUTHORITY_UUID.test(id) || !/^[A-Za-z0-9._:-]{1,200}$/.test(caseId)) {
    return NextResponse.json({ error: 'invalid_scope' }, { status: 400 })
  }
  const query = new URLSearchParams()
  try {
    for (const key of ['effectiveAt', 'knownAt']) {
      const value = req.nextUrl.searchParams.get(key)
      if (value !== null) query.set(key, authorityTimestamp(value))
    }
  } catch { return NextResponse.json({ error: 'invalid_timestamp' }, { status: 400 }) }
  try {
    const response = await fetch(contextServiceUrl(`/api/v1/context/authorizations/${id.toLowerCase()}?${query}`), {
      cache: 'no-store', signal: AbortSignal.timeout(5_000),
      headers: { Accept: 'application/json', Authorization: `Bearer ${session.user.accessToken}`,
        'X-Investigation-Case-Id': caseId, 'X-Investigation-Purpose': 'AUTHORIZATION_REVIEW' },
    })
    if (!response.ok) return NextResponse.json({ error: 'context_unavailable' }, { status: response.status < 504 ? response.status : 502 })
    let history
    try {
      history = parseAuthorityHistory(await response.json())
      if (history.root !== `delegation:${id.toLowerCase()}`) throw new Error('Scope mismatch')
      for (const key of ['effectiveAt', 'knownAt'] as const) {
        const requested = query.get(key)
        if (requested && Date.parse(history[key]) !== Date.parse(requested)) throw new Error('Snapshot mismatch')
      }
    } catch { return NextResponse.json({ error: 'invalid_response' }, { status: 502 }) }
    return NextResponse.json(history, { headers: { 'Cache-Control': 'no-store' } })
  } catch { return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 }) }
}
