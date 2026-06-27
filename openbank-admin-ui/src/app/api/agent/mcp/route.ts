// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

function agentUrl(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://openbank-agent-service:8109/mcp'
  }
  return process.env.AGENT_SERVICE_URL ?? 'http://localhost:8109/mcp'
}

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    // ADR-0031 D3: relay the operator's Keycloak access token so agent-service's @RolesAllowed
    // sees a real authenticated caller. Refuse if there is no operator session — the BFF must
    // never be an unauthenticated relay into the agent surface (ADR-0056).
    const accessToken = (await auth())?.user?.accessToken
    if (!accessToken) {
      return NextResponse.json(
        { jsonrpc: '2.0', id: null, error: { code: -32001, message: 'unauthenticated' } },
        { status: 401 },
      )
    }
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 10000)
    const res = await fetch(agentUrl(), {
      method: 'POST',
      // ADR-0080 P0: assert the control-plane assistant identity so agent-service applies the
      // ui-assistant charter (least-privilege allow-list) and BLOCK-mode enforcement. Without an
      // identity the gate denies by default; this BFF is the operator surface for that one agent.
      headers: {
        'Content-Type': 'application/json',
        'X-Agent-Id': 'ui-assistant',
        'X-Agent-Plane': 'control',
        Authorization: `Bearer ${accessToken}`,
      },
      body: JSON.stringify(body),
      signal: ctrl.signal,
      cache: 'no-store',
    })
    clearTimeout(timer)
    const data = await res.json()
    return NextResponse.json(data, { status: res.status })
  } catch (e) {
    return NextResponse.json(
      { jsonrpc: '2.0', id: null, error: { code: -32603, message: e instanceof Error ? e.message : 'agent_unreachable' } },
      { status: 502 },
    )
  }
}
