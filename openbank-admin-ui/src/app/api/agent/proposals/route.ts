// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BFF proxy for the agent HITL approval queue (ADR-0031 D4). The agent owns the
// proposals store; the admin-ui lists pending proposals and records a human
// decision. GET ?state=pending|all ; POST { proposalId, approve, reason }.
// A legacy decidedBy field may be sent by older clients, but never supplies audit identity.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { loadAgentCharters } from '@/lib/governance/agentCharters'
import { resolveAgentIdentity } from '@/lib/governance/agentIdentity'

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
    const enriched = Array.isArray(rows) ? rows.map(row => {
      const id = row.proposedBy ?? 'unknown'
      const known = resolveAgentIdentity(id, registry).status === 'chartered'
      if (!registry.available) return row
      // A missing AI charter is not proof that this principal is a human.
      if (!known) return { ...row, agent: undefined }
      return {
        ...row,
        agent: {
          id,
          displayName: id.split('-').map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' '),
          icon: 'bot',
          charterKnown: true,
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
    const { proposalId, approve, reason } = body ?? {}
    if (!proposalId || typeof approve !== 'boolean') {
      return NextResponse.json({ error: 'proposalId and approve (bool) are required' }, { status: 400 })
    }
    const session = await auth()
    const accessToken = session?.user?.accessToken
    // Retain the legacy field for a mixed-version agent-service rollout, but source it
    // from the authenticated session, never the browser's decidedBy body property.
    const decidedBy = session?.user?.id?.trim()
    if (!accessToken || !decidedBy) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
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
