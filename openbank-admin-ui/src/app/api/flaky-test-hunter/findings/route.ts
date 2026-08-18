// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

// BFF proxy to the flaky-test-hunter agent (ADR-0168). Returns the active findings for the
// findings list page (/iaops/flaky-test-hunter). Mirrors the devops-agent BFF pattern
// (src/app/api/devops/insights/route.ts): flaky-test-hunter's namespace is not in
// admin-ui.yaml's OPENBANK_NAMESPACES (its Deployment name is neither `openbank-*` nor
// `*-service`, so ADR-0051 discovery never enumerates it), so it is addressed with a
// hardcoded in-cluster Service DNS rather than the generic /api/svc/[service] proxy.

export interface FlakyTestFinding {
  id: string
  checkType:
    | 'RUNBLOCKING_UNIT_MISSING'
    | 'PACT_LOCAL_VERIFICATION_BLIND_SPOT'
    | 'PACT_PROVIDER_CLASS_COLLISION'
    | 'TEST_COUNT_DRIFT'
  severity: 'WARNING' | 'CRITICAL'
  detectedAt: string
  title: string
  component: string
  filePath: string
  rawMetricValue: number
  threshold: number
  rootCause: string | null
  proposalUrl: string | null
  proposedFixDiff: string | null
  status: 'OPEN' | 'DIAGNOSED' | 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'RESOLVED'
  diagnosedAt: string | null
  proposedAt: string | null
}

function flakyTestHunterBase(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://flaky-test-hunter.flaky-test-hunter.svc:8148'
  }
  return process.env.FLAKY_TEST_HUNTER_URL ?? 'http://localhost:8148'
}

export async function GET() {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) return NextResponse.json({ findings: [], available: false, reason: 'unauthorized' }, { status: 401 })

  try {
    const res = await fetch(`${flakyTestHunterBase()}/api/v1/flaky-test-hunter/findings`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      signal: AbortSignal.timeout(10_000),
      cache: 'no-store',
    })
    if (!res.ok) return NextResponse.json({ findings: [], available: false })

    const findings = (await res.json()) as FlakyTestFinding[]
    return NextResponse.json({ findings, available: true })
  } catch {
    return NextResponse.json({ findings: [], available: false })
  }
}
