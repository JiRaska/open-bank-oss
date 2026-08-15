// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Swarm-case list BFF (ADR-0244/ADR-0246). Proxies case-coordinator-agent's
// GET /api/v1/case-coordinator/cases with the operator's bearer (bearer-relay rule —
// 401 before touching the backend when there is no session). Read-only: the UI never
// opens a case, it only renders the Temporal history.
//
// The envelope carries an explicit availability signal so the page can render the
// three honest empty states ADR-0246 D6 requires instead of a raw error:
//   available:true + empty cases  → no cases have been opened yet
//   available:false not_deployed  → case-coordinator has no deployment here
//   available:false unreachable   → DNS/timeout/abort (pod absent, scaled to zero)

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

const VALID_STATUSES = new Set(['OPEN', 'CONVERGING', 'CONTESTED', 'SYNTHESIZED', 'CLOSED'])
const MAX_LIMIT = 200
const DEFAULT_LIMIT = 25

function caseCoordinatorBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://openbank-case-coordinator-agent:8146'
  return (process.env.CASE_COORDINATOR_URL ?? 'http://localhost:8146').replace(/\/$/, '')
}

export async function GET(req: NextRequest) {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) {
    return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  }

  const url = new URL(req.url)
  const status = url.searchParams.get('status')
  if (status && !VALID_STATUSES.has(status)) {
    return NextResponse.json({ error: 'invalid_status' }, { status: 400 })
  }
  const limitRaw = Number(url.searchParams.get('limit') ?? String(DEFAULT_LIMIT))
  const limit = Number.isFinite(limitRaw) ? Math.min(Math.max(Math.trunc(limitRaw), 1), MAX_LIMIT) : DEFAULT_LIMIT

  const params = new URLSearchParams({ limit: String(limit) })
  if (status) params.set('status', status)

  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 10000)
    const res = await fetch(`${caseCoordinatorBase()}/api/v1/case-coordinator/cases?${params.toString()}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      cache: 'no-store',
      signal: ctrl.signal,
    })
    clearTimeout(timer)
    if (res.status === 404) {
      return NextResponse.json({ available: false, reason: 'not_deployed', cases: [] }, { headers: { 'Cache-Control': 'no-store' } })
    }
    if (!res.ok) {
      return NextResponse.json({ available: false, reason: 'error', cases: [] }, { headers: { 'Cache-Control': 'no-store' } })
    }
    const body = await res.json() as { cases?: unknown[] }
    return NextResponse.json(
      { available: true, cases: Array.isArray(body.cases) ? body.cases : [] },
      { headers: { 'Cache-Control': 'no-store' } },
    )
  } catch {
    return NextResponse.json({ available: false, reason: 'unreachable', cases: [] }, { headers: { 'Cache-Control': 'no-store' } })
  }
}
