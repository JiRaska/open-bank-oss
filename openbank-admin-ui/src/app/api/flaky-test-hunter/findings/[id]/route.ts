// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import type { FlakyTestFinding } from '../route'

export const dynamic = 'force-dynamic'

// Single-finding BFF for the /iaops/flaky-test-hunter/[id] detail view. Same base-URL
// convention as the findings-list route — see the comment there.

function flakyTestHunterBase(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://flaky-test-hunter.flaky-test-hunter.svc:8148'
  }
  return process.env.FLAKY_TEST_HUNTER_URL ?? 'http://localhost:8148'
}

export async function GET(_req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const { id } = await ctx.params
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })

  try {
    const res = await fetch(`${flakyTestHunterBase()}/api/v1/flaky-test-hunter/findings/${encodeURIComponent(id)}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      signal: AbortSignal.timeout(10_000),
      cache: 'no-store',
    })
    if (res.status === 404) return NextResponse.json({ error: 'not_found' }, { status: 404 })
    if (!res.ok) return NextResponse.json({ error: 'upstream_error' }, { status: res.status })

    const finding = (await res.json()) as FlakyTestFinding
    return NextResponse.json(finding)
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
