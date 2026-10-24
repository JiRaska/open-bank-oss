// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BFF proxy for the recorded human merges (ADR-0031 phase 3). The agent-service
// owns the merge-record store; the admin-ui Control Room lists WHO merged an
// agent's PR as governance evidence. GET only; optional ?agentId= narrows to one
// agent (the per-agent drill-down). Mirrors the proposals proxy: the operator's
// Keycloak bearer is relayed to agent-service's @RolesAllowed, upstream status is
// preserved, and nothing is cached.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

function agentBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://openbank-agent-service:8109'
  return (process.env.AGENT_SERVICE_URL ?? 'http://localhost:8109/mcp').replace(/\/mcp$/, '')
}

// ADR-0031 D3: the operator's Keycloak access token, relayed to agent-service's @RolesAllowed.
async function operatorBearer(): Promise<string | null> {
  return (await auth())?.user?.accessToken ?? null
}

export async function GET(req: NextRequest) {
  try {
    const accessToken = await operatorBearer()
    if (!accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
    const agentId = req.nextUrl.searchParams.get('agentId')
    const upstream = agentId === null || agentId === ''
      ? `${agentBase()}/api/v1/agent-merge-records`
      : `${agentBase()}/api/v1/agent-merge-records?agentId=${encodeURIComponent(agentId)}`
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 10000)
    const res = await fetch(upstream, {
      headers: { Authorization: `Bearer ${accessToken}` },
      cache: 'no-store',
      signal: ctrl.signal,
    })
    clearTimeout(timer)
    if (!res.ok) return NextResponse.json({ error: 'upstream_error' }, { status: res.status })
    return NextResponse.json(await res.json(), { status: res.status })
  } catch {
    return NextResponse.json({ error: 'agent_unreachable' }, { status: 502 })
  }
}
