// SPDX-License-Identifier: Apache-2.0

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { KYB_UUID } from '@/lib/context/kybOwnership'
import { loadAuthorizedOwnershipHistory } from '@/lib/context/kybOwnershipServer'

export const dynamic = 'force-dynamic'
const fail = (status: number) => NextResponse.json({ error: 'ownership_unavailable' }, { status, headers: { 'Cache-Control': 'no-store' } })

export async function GET(_request: Request, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return fail(401)
  if (!session.user.roles?.some(role => ['ROLE_KYC', 'ROLE_ADMIN'].includes(role))) return fail(403)
  const { id } = await ctx.params
  if (!KYB_UUID.test(id)) return fail(400)
  try {
    const result = await loadAuthorizedOwnershipHistory(id.toLowerCase(), session.user.accessToken)
    if (!result.history) return fail(result.status)
    return NextResponse.json(result.history, { headers: { 'Cache-Control': 'no-store' } })
  } catch { return fail(502) }
}
