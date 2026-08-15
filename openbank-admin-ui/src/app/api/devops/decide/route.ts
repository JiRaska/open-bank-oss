// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

// BFF proxy for the HITL decision on a DevOps finding (ADR-0119 / ADR-0031 D4). A human operator
// approves or rejects a proposed remediation; this forwards to the devops-agent. The admin-UI is
// already behind AuthGuard; the agent enforces @RolesAllowed(ROLE_ADMIN) on its side (it read
// `platform-admin` until #2418 — a role no Keycloak realm has ever issued, so the endpoint 403'd
// for everyone).
//
function devopsBase(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://devops-agent.devops-agent.svc:8142'
  }
  return process.env.DEVOPS_AGENT_URL ?? 'http://localhost:8142'
}

export async function POST(req: NextRequest) {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })

  const { id, action } = (await req.json()) as { id?: string; action?: string }
  if (!id || (action !== 'approve' && action !== 'reject')) {
    return NextResponse.json({ error: 'id and action (approve|reject) are required' }, { status: 400 })
  }
  try {
    const res = await fetch(`${devopsBase()}/api/v1/devops/findings/${encodeURIComponent(id)}/${action}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}` },
      signal: AbortSignal.timeout(10_000),
    })
    if (!res.ok) {
      return NextResponse.json({ error: 'upstream_error' }, { status: res.status })
    }
    return NextResponse.json({ finding: await res.json() })
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
