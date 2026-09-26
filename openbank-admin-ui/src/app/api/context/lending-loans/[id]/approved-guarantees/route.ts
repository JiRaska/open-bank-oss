// SPDX-License-Identifier: Apache-2.0
import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { contextServiceUrl } from '@/lib/context/server'
import { AUTHORITY_UUID } from '@/lib/context/authorityHistory'
import { parseApprovedGuaranteeHistory } from '@/lib/context/lendingGuarantees'
import { readBoundedOwnershipJson } from '@/lib/context/kybOwnershipServer'

export const dynamic = 'force-dynamic'
const fail = (error: string, status: number) => NextResponse.json({ error }, { status, headers: { 'Cache-Control': 'no-store' } })

export async function GET(req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return fail('unauthorized', 401)
  if (!session.user.roles?.some(role => role === 'ROLE_ADMIN' || role === 'ROLE_CREDIT_RISK')) return fail('forbidden', 403)
  const { id } = await ctx.params
  if (!AUTHORITY_UUID.test(id) || [...req.nextUrl.searchParams].length) return fail('invalid_scope', 400)
  const loanId = id.toLowerCase()
  try {
    const upstream = await fetch(contextServiceUrl(`/api/v1/context/lending-loans/${loanId}/approved-guarantees`), {
      cache: 'no-store', signal: AbortSignal.timeout(8_000),
      headers: {
        Accept: 'application/json', Authorization: `Bearer ${session.user.accessToken}`,
        'X-Investigation-Case-Id': loanId, 'X-Investigation-Purpose': 'LENDING_EXPOSURE_REVIEW',
      },
    })
    if (!upstream.ok) return fail('context_unavailable', [401, 403, 503].includes(upstream.status) ? upstream.status : 502)
    let history
    try {
      history = parseApprovedGuaranteeHistory(await readBoundedOwnershipJson(upstream, 96 * 1024))
      if (history.loanId !== loanId) throw new Error('Scope mismatch')
    } catch { return fail('invalid_response', 502) }
    return NextResponse.json(history, { headers: { 'Cache-Control': 'no-store' } })
  } catch { return fail('upstream_unreachable', 502) }
}
