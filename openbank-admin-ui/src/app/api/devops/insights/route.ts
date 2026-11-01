// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import type { DevOpsFinding } from '@/lib/devops/contracts'
import { trustedRepositoryPullRequestUrl } from '@/lib/security/trustedUrls'

export const dynamic = 'force-dynamic'

// BFF proxy to the devops-agent service (ADR-0119). Returns active DevOps
// findings (CI health, DORA regressions, runner capacity, deploy health, SSDLC
// hygiene, incident recurrence). When the service is unreachable, returns an
// empty list (non-blocking — never throws to the page).

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
    const safeFindings = findings.map(finding => ({
      ...finding,
      proposalPrUrl: trustedRepositoryPullRequestUrl(finding.proposalPrUrl),
    }))
    return NextResponse.json({ findings: safeFindings, available: true })
  } catch {
    return NextResponse.json({ findings: [], available: false })
  }
}
