// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

function agentBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://openbank-agent-service:8109'
  return process.env.AGENT_SERVICE_URL?.replace(/\/mcp$/, '') ?? 'http://localhost:8109'
}

/**
 * The browser can request a review, but never reaches a model or agent-service directly.
 * agent-service pins the exact DRAFT, invokes a self-hosted model without tools and persists only
 * a PROPOSED HITL item. The operator bearer is relayed so its own policy remains authoritative.
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json() as { offeringId?: unknown; revisionId?: unknown; model?: unknown }
    if (typeof body.offeringId !== 'string' || typeof body.revisionId !== 'string') {
      return NextResponse.json({ error: 'offeringId and revisionId are required' }, { status: 400 })
    }
    const accessToken = (await auth())?.user?.accessToken
    if (!accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })

    const response = await fetch(`${agentBase()}/agent/catalog-reviews`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` },
      body: JSON.stringify({
        offeringId: body.offeringId,
        revisionId: body.revisionId,
        ...(typeof body.model === 'string' ? { model: body.model } : {}),
      }),
      signal: AbortSignal.timeout(35_000),
      cache: 'no-store',
    })
    return NextResponse.json(await response.json(), { status: response.status })
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : 'agent_unreachable' },
      { status: 502 },
    )
  }
}
