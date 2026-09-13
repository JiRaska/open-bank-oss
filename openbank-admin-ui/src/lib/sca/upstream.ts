// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import 'server-only'
import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

/** Session and role gate for the checker; upstream still enforces OPA and maker separation. */
export async function forwardScaApproval(id: string, decision?: boolean): Promise<NextResponse> {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  if (!session.user.roles?.some(role => role === 'ROLE_OPERATOR' || role === 'ROLE_ADMIN')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
    return NextResponse.json({ error: 'invalid_approval_id' }, { status: 400 })
  }
  try {
    const response = await fetch(serverSvcUrl('sca-service', 'sca', 8110, `/api/v1/sca/approvals/${id}`), {
      method: decision === undefined ? 'GET' : 'PATCH',
      headers: { Authorization: `Bearer ${session.user.accessToken}`, 'Content-Type': 'application/json' },
      body: decision === undefined ? undefined : JSON.stringify({ approve: decision }),
      cache: 'no-store',
      signal: AbortSignal.timeout(8000),
    })
    if (!response.ok) {
      const status = [400, 401, 403, 404, 409].includes(response.status) ? response.status : 502
      return NextResponse.json({ error: 'upstream_error' }, { status })
    }
    return NextResponse.json(await response.json(), { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
