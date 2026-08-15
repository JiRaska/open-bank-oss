// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Swarm-case detail BFF (ADR-0244/ADR-0246). Proxies case-coordinator-agent's
// GET /api/v1/case-coordinator/cases/{caseId} — the full thread (CASE_OPENED /
// CONTRIBUTION / PROPOSAL_EMITTED entries, oldest first) for the thread view.
// Bearer-relay rule applies: 401 before touching the backend when there is no session.
//
// 404 semantics: a JSON 404 from the upstream means the service IS up and the caseId
// is unknown (an absent pod fails at DNS/connect, i.e. the catch branch below), so
// forwarding a 404 here is honest "no such case", not "not deployed".

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

const CASE_ID_PATTERN = /^[A-Za-z0-9._-]{1,200}$/

function caseCoordinatorBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://openbank-case-coordinator-agent:8146'
  return (process.env.CASE_COORDINATOR_URL ?? 'http://localhost:8146').replace(/\/$/, '')
}

export async function GET(_req: NextRequest, ctx: { params: Promise<{ caseId: string }> }) {
  const { caseId } = await ctx.params
  if (!CASE_ID_PATTERN.test(caseId)) {
    return NextResponse.json({ error: 'invalid_case_id' }, { status: 400 })
  }

  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) {
    return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  }

  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 10000)
    const res = await fetch(
      `${caseCoordinatorBase()}/api/v1/case-coordinator/cases/${encodeURIComponent(caseId)}`,
      { headers: { Authorization: `Bearer ${accessToken}` }, cache: 'no-store', signal: ctrl.signal },
    )
    clearTimeout(timer)
    if (res.status === 404) {
      return NextResponse.json({ error: 'not_found' }, { status: 404, headers: { 'Cache-Control': 'no-store' } })
    }
    if (!res.ok) {
      return NextResponse.json({ available: false, reason: 'error' }, { headers: { 'Cache-Control': 'no-store' } })
    }
    const thread = await res.json()
    return NextResponse.json({ available: true, thread }, { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json({ available: false, reason: 'unreachable' }, { headers: { 'Cache-Control': 'no-store' } })
  }
}
