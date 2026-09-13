// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Read-only BFF edge for immutable delegation lifecycle approval evidence. Intentionally GET-only:
// the browser cannot propose, approve, reject or execute through admin-ui.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }

  const { id } = await params
  if (!UUID_RE.test(id)) {
    return NextResponse.json({ error: 'invalid_approval_id' }, { status: 400 })
  }

  try {
    const upstream = await fetch(serverSvcUrl(
      'delegation-service',
      'delegation',
      8126,
      `/api/v1/delegations/approvals/${id}`,
    ), {
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      cache: 'no-store',
      signal: AbortSignal.timeout(4000),
    })
    if (upstream.status === 404) {
      return NextResponse.json({ error: 'not_found' }, { status: 404 })
    }
    if (!upstream.ok) {
      const status = upstream.status === 401 || upstream.status === 403 ? 403 : 502
      return NextResponse.json({ error: status === 403 ? 'forbidden' : 'upstream_error' }, { status })
    }
    return NextResponse.json(await upstream.json())
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
