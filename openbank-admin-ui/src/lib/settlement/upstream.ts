// SPDX-License-Identifier: Apache-2.0
import 'server-only'
import { NextResponse } from 'next/server'
import { z } from 'zod'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'
import { settlementDetailsSchema } from '@/lib/settlement/status'

const headers = { 'Cache-Control': 'no-store' }
const failure = (status: number, error: string) => NextResponse.json({ error }, { status, headers })

export async function readSettlement(id: string) {
  const session = await auth()
  if (!session?.user?.accessToken) return failure(401, 'unauthorized')
  if (!session.user.roles?.some(role => role === 'ROLE_OPERATOR' || role === 'ROLE_ADMIN')) {
    return failure(403, 'forbidden')
  }
  if (!z.uuid().safeParse(id).success) return failure(400, 'invalid_settlement_id')
  try {
    const response = await fetch(serverSvcUrl('settlement-service', 'payments', 8138, `/api/v1/settlements/${id}`), {
      headers: { Authorization: `Bearer ${session.user.accessToken}` },
      cache: 'no-store', signal: AbortSignal.timeout(8000),
    })
    if (!response.ok) {
      const status = [400, 401, 403, 404].includes(response.status) ? response.status : 502
      return failure(status, status === 404 ? 'settlement_not_found' : 'upstream_error')
    }
    const detail = settlementDetailsSchema.safeParse(await response.json())
    if (!detail.success || detail.data.id.toLowerCase() !== id.toLowerCase()) {
      return failure(502, 'invalid_upstream_response')
    }
    return NextResponse.json(detail.data, { headers })
  } catch {
    return failure(502, 'upstream_unreachable')
  }
}
