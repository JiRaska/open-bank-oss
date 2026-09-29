// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

interface KillSwitchScope { scope: string; reason: string; setBy: string }

interface KillSwitchStatus {
  available: true
  active: boolean
  scopes: KillSwitchScope[]
}

interface KillSwitchUnavailable {
  available: false
  reason: 'not_deployed' | 'unreachable' | 'error'
  active: false
  scopes: []
}

type KillSwitchEnvelope = KillSwitchStatus | KillSwitchUnavailable

function caseCoordinatorBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://openbank-case-coordinator-agent:8146'
  return (process.env.CASE_COORDINATOR_URL ?? 'http://localhost:8146').replace(/\/$/, '')
}

function unavailable(reason: KillSwitchUnavailable['reason']): NextResponse<KillSwitchUnavailable> {
  return NextResponse.json(
    { available: false, reason, active: false, scopes: [] },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

export async function GET(_req: NextRequest) {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) {
    return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  }

  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 10000)
    const res = await fetch(`${caseCoordinatorBase()}/api/v1/case-coordinator/kill-switch`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      cache: 'no-store',
      signal: ctrl.signal,
    })
    clearTimeout(timer)
    if (res.status === 404) {
      return unavailable('not_deployed')
    }
    if (!res.ok) {
      return unavailable('error')
    }
    const body = (await res.json()) as Partial<KillSwitchStatus>
    return NextResponse.json(
      {
        available: true,
        active: !!body.active,
        scopes: Array.isArray(body.scopes) ? body.scopes : [],
      } satisfies KillSwitchStatus,
      { headers: { 'Cache-Control': 'no-store' } },
    )
  } catch {
    return unavailable('unreachable')
  }
}
