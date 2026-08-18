// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import type { FlakyTestFinding } from '../findings/route'

export interface FlakyTestReport {
  runId: string
  startedAt: string
  completedAt: string
  testFilesScanned: number
  findingsDetected: FlakyTestFinding[]
  findingsProposed: number
  tokensUsed: number
  trigger: 'SCHEDULED' | 'CI_TEST_SUITE_FAILURE_WEBHOOK' | 'OPERATOR_MANUAL'
}

export const dynamic = 'force-dynamic'

// BFF proxy for the operator "Run check now" trigger (ADR-0168, POST /check/trigger). The
// admin-ui is already behind AuthGuard and the button itself only renders for
// hasPermission(roles, 'flaky-test-hunter:trigger') (ROLE_ADMIN — see roles.ts), but that is
// UX-only, same as the devops-agent decide route: the operator's own Keycloak bearer is
// relayed as-is and FlakyTestResource enforces @RolesAllowed("ROLE_ADMIN") on its side, so a
// spoofed client request still gets a real 403.

function flakyTestHunterBase(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://flaky-test-hunter.flaky-test-hunter.svc:8148'
  }
  return process.env.FLAKY_TEST_HUNTER_URL ?? 'http://localhost:8148'
}

export async function POST() {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })

  try {
    const res = await fetch(`${flakyTestHunterBase()}/api/v1/flaky-test-hunter/check/trigger`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}` },
      // The check walks the whole test tree (RunBlocking scan, Pact class scan, JUnit XML
      // diff) synchronously and returns the completed report — give it real headroom rather
      // than the usual 10s BFF timeout.
      signal: AbortSignal.timeout(60_000),
    })
    if (res.status === 403) return NextResponse.json({ error: 'forbidden' }, { status: 403 })
    if (!res.ok) return NextResponse.json({ error: 'upstream_error' }, { status: res.status })

    return NextResponse.json(await res.json())
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
