// SPDX-License-Identifier: Apache-2.0

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import { serverSvcUrl } from '@/lib/services/bff'
import { DISPUTE_STATUSES, parseDisputeList, type DisputeRecord } from '@/lib/disputes/disputePortfolio'

export const dynamic = 'force-dynamic'
const TIMEOUT_MS = 8_000

class DisputeHttpError extends Error {
  constructor(readonly status: number) {
    super(`dispute-service HTTP ${status}`)
  }
}

class InvalidDisputeResponseError extends Error {}

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  }
  if (!hasPermission(session.user.roles ?? [], 'compliance:view')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }

  try {
    const pages = await Promise.all(DISPUTE_STATUSES.map(async status => {
      const response = await fetch(serverSvcUrl(
        'dispute-service',
        'dispute',
        8135,
        '/api/v1/disputes',
        { status },
      ), {
        cache: 'no-store',
        headers: {
          Accept: 'application/json',
          Authorization: `Bearer ${session.user.accessToken}`,
        },
        signal: AbortSignal.timeout(TIMEOUT_MS),
      })
      if (!response.ok) throw new DisputeHttpError(response.status)
      let disputes: DisputeRecord[]
      try {
        disputes = parseDisputeList(await response.json())
      } catch {
        throw new InvalidDisputeResponseError('invalid dispute-service payload')
      }
      if (disputes.some(dispute => dispute.status !== status)) {
        throw new InvalidDisputeResponseError('dispute-service returned a mismatched status page')
      }
      return disputes
    }))

    // Concurrent status reads can observe a transition twice. De-duplicate by aggregate id so one
    // case never inflates totals; the later status in the canonical lifecycle order wins.
    const unique = new Map<string, DisputeRecord>()
    for (const page of pages) for (const dispute of page) unique.set(dispute.id, dispute)
    return NextResponse.json([...unique.values()])
  } catch (error) {
    if (error instanceof DisputeHttpError && (error.status === 401 || error.status === 403)) {
      return NextResponse.json(
        { error: error.status === 401 ? 'unauthorized' : 'forbidden' },
        { status: error.status },
      )
    }
    if (error instanceof InvalidDisputeResponseError) {
      return NextResponse.json({ error: 'invalid_upstream_response' }, { status: 502 })
    }
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
