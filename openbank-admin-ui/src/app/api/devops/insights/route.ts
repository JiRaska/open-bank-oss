// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

// BFF proxy to the devops-agent service (ADR-0119). Returns active DevOps
// findings (CI health, DORA regressions, runner capacity, deploy health, SSDLC
// hygiene, incident recurrence). When the service is unreachable, returns an
// empty list (non-blocking — never throws to the page).

export interface DevOpsFinding {
  id: string
  detector:
    | 'D1_CI_PIPELINE_HEALTH'
    | 'D2_DORA_REGRESSION'
    | 'D3_RUNNER_CAPACITY'
    | 'D4_DEPLOY_HEALTH'
    | 'D5_SSDLC_HYGIENE'
    | 'D6_INCIDENT_RECURRENCE'
  severity: 'WARNING' | 'CRITICAL'
  detectedAt: string
  title: string
  rawMetricValue: number
  threshold: number
  affectedResource: string
  doraMetricImpacted:
    | 'DEPLOYMENT_FREQUENCY'
    | 'LEAD_TIME_FOR_CHANGES'
    | 'CHANGE_FAILURE_RATE'
    | 'TIME_TO_RESTORE'
    | null
  rootCause: string | null
  remediationKind: 'PULL_REQUEST' | 'RUNBOOK_UPDATE' | 'TICKET' | 'NONE'
  proposalPrUrl: string | null
  proposedRemediation: string | null
  status: 'OPEN' | 'DIAGNOSED' | 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'RESOLVED'
  diagnosedAt: string | null
  proposedAt: string | null
}

function devopsBase(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://devops-agent.devops-agent.svc:8142'
  }
  return process.env.DEVOPS_AGENT_URL ?? 'http://localhost:8142'
}

export async function GET() {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) return NextResponse.json({ findings: [], available: false, reason: 'unauthorized' }, { status: 401 })

  try {
    const res = await fetch(`${devopsBase()}/api/v1/devops/findings`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      signal: AbortSignal.timeout(10_000),
    })
    if (!res.ok) return NextResponse.json({ findings: [], available: false })

    const findings = (await res.json()) as DevOpsFinding[]
    return NextResponse.json({ findings, available: true })
  } catch {
    return NextResponse.json({ findings: [], available: false })
  }
}
