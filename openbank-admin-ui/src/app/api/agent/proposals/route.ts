// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BFF proxy for the agent HITL approval queue (ADR-0031 D4). The agent owns the
// proposals store; the admin-ui lists pending proposals and records a human
// decision. GET ?state=pending|all ; POST { proposalId, approve, decidedBy, reason }.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { loadAgentCharters } from '@/lib/governance/agentCharters'

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
  const state = req.nextUrl.searchParams.get('state') ?? 'pending'
  try {
    const accessToken = await operatorBearer()
    if (!accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 10000)
    const res = await fetch(`${agentBase()}/api/v1/proposals?state=${encodeURIComponent(state)}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      cache: 'no-store',
      signal: ctrl.signal,
    })
    clearTimeout(timer)
    if (!res.ok) return NextResponse.json({ error: 'upstream_error' }, { status: res.status })
    const rows = await res.json() as { proposedBy?: string }[]
    const registry = await loadAgentCharters()
    // Without a readable registry we cannot classify an author as a human. Preserve the
    // upstream provenance so the UI can apply its conservative fallback (ADR-0080).
    const charterIds = new Set(registry.agents.map(agent => agent.id))
    const enriched = Array.isArray(rows) ? rows.map(row => {
      const id = row.proposedBy ?? 'unknown'
      const known = charterIds.has(id)
      if (!registry.available) return row
      return {
        ...row,
        agent: {
          id,
          displayName: id.split('-').map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' '),
          icon: known ? 'bot' : 'user',
          charterKnown: known,
        },
      }
    }) : rows
    return NextResponse.json(enriched, { status: res.status })
  } catch {
    return NextResponse.json({ error: 'agent_unreachable' }, { status: 502 })
  }
}

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const { proposalId, approve, decidedBy, reason } = body ?? {}
    if (!proposalId || typeof approve !== 'boolean' || !decidedBy) {
      return NextResponse.json({ error: 'proposalId, approve (bool) and decidedBy are required' }, { status: 400 })
    }
    const accessToken = await operatorBearer()
    if (!accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 10000)
    const res = await fetch(`${agentBase()}/api/v1/proposals/${encodeURIComponent(proposalId)}/decision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` },
      body: JSON.stringify({ approve, decidedBy, reason: reason ?? null }),
      signal: ctrl.signal,
      cache: 'no-store',
    })
    clearTimeout(timer)
    if (!res.ok) return NextResponse.json({ error: 'upstream_error' }, { status: res.status })
    return NextResponse.json(await res.json(), { status: res.status })
  } catch {
    return NextResponse.json({ error: 'agent_unreachable' }, { status: 502 })
  }
}
