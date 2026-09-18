// SPDX-License-Identifier: Apache-2.0
import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { contextServiceUrl } from '@/lib/context/server'
import { AUTHORITY_UUID } from '@/lib/context/authorityHistory'
import { parseFraudCaseNetwork } from '@/lib/context/fraudCaseNetwork'

export const dynamic = 'force-dynamic'
const fail = (error: string, status: number) => NextResponse.json({ error }, { status, headers: { 'Cache-Control': 'no-store' } })

export async function GET(req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return fail('unauthorized', 401)
  if (!session.user.roles?.includes('ROLE_ADMIN')) return fail('forbidden', 403)
  const { id } = await ctx.params
  if (!AUTHORITY_UUID.test(id) || [...req.nextUrl.searchParams].length) return fail('invalid_scope', 400)
  const rootId = id.toLowerCase()
  try {
    const response = await fetch(contextServiceUrl(`/api/v1/context/fraud-cases/${rootId}/network`), {
      cache: 'no-store', signal: AbortSignal.timeout(8_000),
      headers: {
        Accept: 'application/json', Authorization: `Bearer ${session.user.accessToken}`,
        'X-Investigation-Case-Id': rootId, 'X-Investigation-Purpose': 'FRAUD_INVESTIGATION',
      },
    })
    if (!response.ok) return fail('context_unavailable', [401, 403, 503].includes(response.status) ? response.status : 502)
    let network
    try {
      network = parseFraudCaseNetwork(await response.json())
      if (network.root.caseId !== rootId) throw new Error('Scope mismatch')
    } catch { return fail('invalid_response', 502) }
    return NextResponse.json(network, { headers: { 'Cache-Control': 'no-store' } })
  } catch { return fail('upstream_unreachable', 502) }
}
