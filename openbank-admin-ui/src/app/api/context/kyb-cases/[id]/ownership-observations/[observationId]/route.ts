// SPDX-License-Identifier: Apache-2.0

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { KYB_UUID, parseOwnershipDetail } from '@/lib/context/kybOwnership'
import { loadAuthorizedOwnershipHistory } from '@/lib/context/kybOwnershipServer'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'
const fail = (status: number) => NextResponse.json({ error: 'ownership_unavailable' }, { status, headers: { 'Cache-Control': 'no-store' } })

export async function GET(_request: Request, ctx: { params: Promise<{ id: string; observationId: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return fail(401)
  if (!session.user.roles?.some(role => ['ROLE_KYC', 'ROLE_ADMIN'].includes(role))) return fail(403)
  const { id, observationId } = await ctx.params
  if (!KYB_UUID.test(id) || !KYB_UUID.test(observationId)) return fail(400)
  const caseId = id.toLowerCase(), selectedId = observationId.toLowerCase()
  try {
    const result = await loadAuthorizedOwnershipHistory(caseId, session.user.accessToken)
    if (!result.history) return fail(result.status)
    const reference = result.history.observations.find(item => item.observationId.toLowerCase() === selectedId)
    if (!reference) return fail(404)
    const response = await fetch(serverSvcUrl('kyb-service', 'kyb', 8157,
      `/api/v1/kyb/cases/${caseId}/ubo-observations/${selectedId}`), {
      cache: 'no-store', signal: AbortSignal.timeout(5_000),
      headers: {
        Accept: 'application/json', Authorization: `Bearer ${session.user.accessToken}`,
        'X-Investigation-Purpose': 'KYB_OWNERSHIP_REVIEW',
      },
    })
    if (!response.ok) return fail([401, 403, 404, 429, 503].includes(response.status) ? response.status : 502)
    const detail = parseOwnershipDetail(await response.json(), reference, caseId, result.history)
    return NextResponse.json(detail, { headers: { 'Cache-Control': 'no-store' } })
  } catch { return fail(502) }
}
