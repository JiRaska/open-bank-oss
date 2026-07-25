// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

// BFF proxy for the HITL decision on a DevOps finding (ADR-0119 / ADR-0031 D4). A human operator
// approves or rejects a proposed remediation; this forwards to the devops-agent. The admin-UI is
// already behind AuthGuard; the agent enforces @RolesAllowed(ROLE_ADMIN) on its side (it read
// `platform-admin` until #2418 — a role no Keycloak realm has ever issued, so the endpoint 403'd
// for everyone).
//
// NOTE: the fetch below sends no Authorization header, unlike the sibling proxies under
// src/app/api/agent/ and src/app/api/svc/. An unauthenticated request cannot satisfy any
// @RolesAllowed, so this path stays broken on the agent side even now that the role name is real —
// the M2M token story is issue #2442. Do not read the corrected comment as "this works".

function devopsBase(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://devops-agent.devops-agent.svc:8142'
  }
  return process.env.DEVOPS_AGENT_URL ?? 'http://localhost:8142'
}

export async function POST(req: NextRequest) {
  const { id, action } = (await req.json()) as { id?: string; action?: string }
  if (!id || (action !== 'approve' && action !== 'reject')) {
    return NextResponse.json({ error: 'id and action (approve|reject) are required' }, { status: 400 })
  }
  try {
    const res = await fetch(`${devopsBase()}/api/v1/devops/findings/${encodeURIComponent(id)}/${action}`, {
      method: 'POST',
      signal: AbortSignal.timeout(10_000),
    })
    if (!res.ok) {
      return NextResponse.json({ error: `devops-agent returned ${res.status}` }, { status: res.status })
    }
    return NextResponse.json({ finding: await res.json() })
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    return NextResponse.json({ error: `decision failed: ${msg}` }, { status: 502 })
  }
}
