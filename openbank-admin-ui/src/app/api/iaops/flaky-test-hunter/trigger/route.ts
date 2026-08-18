// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasRole, ROLES } from '@/lib/auth/roles'

export const dynamic = 'force-dynamic'

// This is deliberately not a generic agent proxy. The route names one bounded
// operator action and one in-cluster service, so a browser session cannot turn
// Admin UI into an arbitrary cluster relay.
function flakyTestHunterBase(): string {
  return (process.env.FLAKY_TEST_HUNTER_URL ?? 'http://localhost:8148').replace(/\/$/, '')
}

export async function POST() {
  const session = await auth()
  const accessToken = session?.user?.accessToken
  if (!accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  if (!hasRole(session.user.roles ?? [], ROLES.ADMIN)) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }

  try {
    const upstream = await fetch(`${flakyTestHunterBase()}/api/v1/flaky-test-hunter/check/trigger-async`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}` },
      // The endpoint only asks Temporal to start the durable workflow and returns
      // 202. It must not wait for a fleet scan/LLM diagnosis, which can take minutes.
      signal: AbortSignal.timeout(10_000),
      cache: 'no-store',
    })
    if (!upstream.ok) return NextResponse.json({ error: 'upstream_error' }, { status: upstream.status })
    const body = await upstream.json().catch(() => null) as { workflowId?: unknown } | null
    if (!body || typeof body.workflowId !== 'string' || !body.workflowId.trim()) {
      return NextResponse.json({ error: 'upstream_invalid_response' }, { status: 502 })
    }
    return NextResponse.json({ workflowId: body.workflowId }, { status: upstream.status })
  } catch (error: unknown) {
    const errorName = error && typeof error === 'object' && 'name' in error ? String(error.name) : ''
    if (errorName === 'AbortError' || errorName === 'TimeoutError') {
      // A timed-out POST can have reached Temporal. Do not misrepresent this as an outage
      // or as a failed start; the operator must check the durable workflow history first.
      return NextResponse.json({ error: 'admission_outcome_unknown' }, { status: 504 })
    }
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
