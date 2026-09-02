// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Read-only BFF projection of audit-service evidence for one delegation grant. The browser never
// receives the raw audit payload: only the fields needed to explain the lifecycle timeline leave
// this server boundary. audit-service still performs its own @RolesAllowed + OPA decision.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import { projectDelegationAuditTimeline } from '@/lib/delegations/auditTimeline'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const TIMEOUT_MS = 4_000
const LIMIT = 100

export async function GET(
  _req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }

  const roles = session.user.roles ?? []
  if (!hasPermission(roles, 'delegations:view') || !hasPermission(roles, 'audit:view')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }

  const { id } = await params
  if (!UUID_RE.test(id)) {
    return NextResponse.json({ error: 'invalid_delegation_id' }, { status: 400 })
  }

  try {
    const upstream = await fetch(
      serverSvcUrl('audit-service', 'audit', 8113, `/api/v1/audit/entries/${id}`, { limit: String(LIMIT) }),
      {
        headers: {
          accept: 'application/json',
          authorization: `Bearer ${session.user.accessToken}`,
        },
        signal: AbortSignal.timeout(TIMEOUT_MS),
        cache: 'no-store',
      },
    )

    if (!upstream.ok) {
      const forbidden = upstream.status === 401 || upstream.status === 403
      return NextResponse.json(
        { error: forbidden ? 'forbidden' : 'upstream_error' },
        { status: forbidden ? 403 : 502 },
      )
    }

    const projection = projectDelegationAuditTimeline(await upstream.json(), id, LIMIT)
    if (!projection) {
      return NextResponse.json({ error: 'invalid_upstream_response' }, { status: 502 })
    }

    return NextResponse.json(projection, { headers: { 'cache-control': 'no-store' } })
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
