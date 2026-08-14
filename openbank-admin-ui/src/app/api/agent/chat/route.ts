// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

function agentBase(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://openbank-agent-service:8109'
  }
  return process.env.AGENT_SERVICE_URL?.replace(/\/mcp$/, '') ?? 'http://localhost:8109'
}

// ADR-0031 D3: the operator's Keycloak access token, relayed to agent-service's @RolesAllowed.
async function operatorBearer(): Promise<string | null> {
  return (await auth())?.user?.accessToken ?? null
}

// Server-side only: the model call lives behind agent-service, never in the browser.
export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const accessToken = await operatorBearer()
    if (!accessToken) {
      return NextResponse.json({ reply: '', model: '', toolCalls: [], error: 'unauthenticated' }, { status: 401 })
    }
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 30000)
    const res = await fetch(`${agentBase()}/agent/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` },
      body: JSON.stringify(body),
      signal: ctrl.signal,
      cache: 'no-store',
    })
    clearTimeout(timer)
    if (!res.ok) return NextResponse.json({ reply: '', model: '', toolCalls: [], error: 'agent_unreachable' }, { status: res.status })
    const data = await res.json()
    return NextResponse.json(data, { status: res.status })
  } catch {
    return NextResponse.json(
      { reply: '', model: '', toolCalls: [], error: 'agent_unreachable' },
      { status: 502 },
    )
  }
}

export async function GET() {
  try {
    const accessToken = await operatorBearer()
    if (!accessToken) {
      return NextResponse.json({ default: '', models: [], error: 'unauthenticated' }, { status: 401 })
    }
    const res = await fetch(`${agentBase()}/agent/models`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      cache: 'no-store',
    })
    if (!res.ok) return NextResponse.json({ default: '', models: [], error: 'agent_unreachable' }, { status: res.status })
    return NextResponse.json(await res.json(), { status: res.status })
  } catch {
    return NextResponse.json({ default: '', models: [], error: 'agent_unreachable' }, { status: 502 })
  }
}
