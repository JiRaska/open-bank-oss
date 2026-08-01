// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0227 D2 (phase 1): the federated approval inbox read. One BFF route merges each
// domain's pending queue — lending's maker-checker approvals plus the agent plane's
// proposals — into one canonical item shape for the /approvals screen. Read-only by
// design: disposal happens in the governed per-domain decide flows (money-path disposal
// additionally requires SCA, ADR-0227 D4 — that is the phase-2 design, deliberately NOT a
// one-click button here).

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

type SourceState = 'ok' | 'forbidden' | 'unavailable'

type InboxItem = {
  id: string
  domain: 'lending' | 'agent'
  action: string
  resourceId: string | null
  maker: string | null
  proposedAt: string | null
}

type LendingApproval = {
  id: string
  action: string
  resourceId: string | null
  makerId: string | null
  createdAt: string | null
}

type AgentProposal = {
  id: string
  suggestedAction: string
  proposedBy: string
  proposedAt: string
}

type SourceResult = { items: InboxItem[]; state: SourceState }

/**
 * A refused read must never render as an empty queue. lending's list is
 * @RolesAllowed(LENDING_OFFICER, CREDIT_RISK, ADMIN) while /approvals itself is open to any
 * authenticated operator, so 403 is the ORDINARY outcome for a supervisor or viewer - and
 * "no approvals pending" is the most dangerous thing an approvals screen can say wrongly.
 * The per-source state travels to the client, which shows it.
 */
function stateFor(status: number): SourceState {
  return status === 401 || status === 403 ? 'forbidden' : 'unavailable'
}

async function lendingPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('lending-service', 'lending', 8126, '/api/v1/lending/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as LendingApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'lending' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

function agentBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://openbank-agent-service:8109'
  return (process.env.AGENT_SERVICE_URL ?? 'http://localhost:8109/mcp').replace(/\/mcp$/, '')
}

async function agentPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(`${agentBase()}/api/v1/proposals?state=proposed`, {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as AgentProposal[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'agent' as const, action: r.suggestedAction,
      resourceId: null, maker: r.proposedBy, proposedAt: r.proposedAt,
    })),
  }
}

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const headers = { authorization: `Bearer ${session.user.accessToken}` }
  const unavailable: SourceResult = { items: [], state: 'unavailable' }
  const [lending, agent] = await Promise.all([
    lendingPending(headers).catch(() => unavailable),
    agentPending(headers).catch(() => unavailable),
  ])
  const items = [...lending.items, ...agent.items]
    .sort((a, b) => (a.proposedAt ?? '').localeCompare(b.proposedAt ?? ''))
  return NextResponse.json({ items, sources: { lending: lending.state, agent: agent.state } })
}
