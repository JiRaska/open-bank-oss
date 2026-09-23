// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

interface KillSwitchScope { scope: string; reason: string; setBy: string }
interface KillSwitchStatus { active: boolean; scopes: KillSwitchScope[] }

function caseCoordinatorBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://openbank-case-coordinator-agent:8146'
  return (process.env.CASE_COORDINATOR_URL ?? 'http://localhost:8146').replace(/\/$/, '')
}

export async function GET(_req: NextRequest) {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) {
    return NextResponse.json({ active: false, scopes: [] as KillSwitchScope[] }, { status: 401 })
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
    if (!res.ok) {
      return NextResponse.json(
        { active: false, scopes: [] as KillSwitchScope[] },
        { headers: { 'Cache-Control': 'no-store' } },
      )
    }
    const body = (await res.json()) as Partial<KillSwitchStatus>
    return NextResponse.json(
      { active: !!body.active, scopes: Array.isArray(body.scopes) ? body.scopes : [] },
      { headers: { 'Cache-Control': 'no-store' } },
    )
  } catch {
    return NextResponse.json(
      { active: false, scopes: [] as KillSwitchScope[] },
      { headers: { 'Cache-Control': 'no-store' } },
    )
  }
}
