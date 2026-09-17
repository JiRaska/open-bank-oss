// SPDX-License-Identifier: Apache-2.0
import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { contextServiceUrl } from '@/lib/context/server'
import { AUTHORITY_UUID, authorityTimestamp } from '@/lib/context/authorityHistory'
import { amlTimestampNanos, parseAmlCaseEvidence, parseAmlCaseNetwork } from '@/lib/context/amlCaseEvidence'

export const dynamic = 'force-dynamic'
const fail = (error: string, status: number) => NextResponse.json({ error }, { status, headers: { 'Cache-Control': 'no-store' } })
export async function GET(req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return fail('unauthorized', 401)
  if (!session.user.roles?.some(role => ['ROLE_ADMIN', 'ROLE_COMPLIANCE'].includes(role))) return fail('forbidden', 403)
  const { id } = await ctx.params
  if (!AUTHORITY_UUID.test(id)) return fail('invalid_scope', 400)
  const view = req.nextUrl.searchParams.getAll('view')
  if (view.length > 1 || (view.length === 1 && view[0] !== 'network')) return fail('invalid_view', 400)
  const network = view.length === 1
  const rootId = id.toLowerCase(), query = new URLSearchParams()
  try {
    for (const key of ['effectiveAt', 'knownAt']) {
      const values = req.nextUrl.searchParams.getAll(key)
      if (values.length > 1) throw new Error('Duplicate timestamp')
      if (values.length) query.set(key, authorityTimestamp(values[0]))
    }
  } catch { return fail('invalid_timestamp', 400) }
  try {
    const response = await fetch(contextServiceUrl(`/api/v1/context/aml-cases/${rootId}${network ? '/network' : ''}?${query}`), {
      cache: 'no-store', signal: AbortSignal.timeout(5_000),
      headers: { Accept: 'application/json', Authorization: `Bearer ${session.user.accessToken}`,
        'X-Investigation-Case-Id': rootId, 'X-Investigation-Purpose': 'AML_INVESTIGATION' },
    })
    if (!response.ok) return fail('context_unavailable', [401, 403, 404, 429, 503].includes(response.status) ? response.status : 502)
    let history
    try {
      const raw = await response.json()
      history = network ? parseAmlCaseNetwork(raw) : parseAmlCaseEvidence(raw)
      const scoped = 'related' in history ? history.root : history
      if (scoped.root !== `aml-case:${rootId}`) throw new Error('Scope mismatch')
      for (const key of ['effectiveAt', 'knownAt'] as const) {
        const requested = query.get(key)
        if (requested && amlTimestampNanos(scoped[key]) !== amlTimestampNanos(requested)) throw new Error('Snapshot mismatch')
      }
    } catch { return fail('invalid_response', 502) }
    return NextResponse.json(history, { headers: { 'Cache-Control': 'no-store' } })
  } catch { return fail('upstream_unreachable', 502) }
}
