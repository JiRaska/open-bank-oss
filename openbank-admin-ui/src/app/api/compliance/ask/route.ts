// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Question -> relevant trace rows (ADR-0379 phase 3). The question goes to agent-service's governed
// chat (ADR-0031) with tool execution disabled. The model may only answer with row tokens; a reply
// containing anything else is withheld (422). The response carries the selected rows' computed
// facts and authored YAML text, never model prose, so the model can point at evidence but cannot
// state a claim.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { loadComplianceTrace } from '@/lib/compliance/trace-server'
import { buildSelectionContext, parseSelection, selectedRows } from '@/lib/compliance/tools'
import { hasPermission } from '@/lib/auth/roles'

export const dynamic = 'force-dynamic'

const MAX_QUESTION = 1000

function agentBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://openbank-agent-service:8109'
  return process.env.AGENT_SERVICE_URL?.replace(/\/mcp$/, '') ?? 'http://localhost:8109'
}

export async function POST(request: Request) {
  const session = await auth()
  const accessToken = session?.user?.accessToken
  if (!accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  // The route guard already requires this; checked again so a guard regression cannot open it.
  if (!hasPermission(session.user?.roles ?? [], 'compliance:view')) return NextResponse.json({ error: 'forbidden' }, { status: 403 })

  let question: unknown
  try {
    question = ((await request.json()) as { question?: unknown }).question
  } catch {
    return NextResponse.json({ error: 'invalid_json' }, { status: 400 })
  }
  if (typeof question !== 'string' || !question.trim() || question.length > MAX_QUESTION) {
    return NextResponse.json({ error: 'question must be a non-empty string of at most 1000 characters' }, { status: 400 })
  }

  const trace = await loadComplianceTrace()
  if (trace.status !== 'available') return NextResponse.json({ error: 'trace_unavailable', missing: trace.missing }, { status: 503 })

  let reply = ''
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), 30000)
  try {
    const res = await fetch(`${agentBase()}/agent/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` },
      body: JSON.stringify({
        messages: [{ role: 'user', content: question.trim() }],
        context: buildSelectionContext(trace),
        allowTools: false,
      }),
      signal: ctrl.signal,
      cache: 'no-store',
    })
    if (!res.ok) return NextResponse.json({ error: 'agent_unreachable' }, { status: 502 })
    const body = (await res.json()) as { reply?: unknown; toolsRestricted?: unknown }
    // allowTools:false is a request. Only the server's acknowledgement proves the model ran
    // without tools; an agent-service that predates the flag would silently ignore it.
    if (body.toolsRestricted !== true) return NextResponse.json({ error: 'tools_not_restricted' }, { status: 502 })
    reply = String(body.reply ?? '')
  } catch {
    return NextResponse.json({ error: 'agent_unreachable' }, { status: 502 })
  } finally {
    clearTimeout(timer)
  }

  const selection = parseSelection(reply, trace)
  if (!selection.ok) {
    return NextResponse.json({ error: selection.extraText ? 'not_tokens_only' : 'no_rows', unknown: selection.unknown }, { status: 422 })
  }
  return NextResponse.json({ rows: selectedRows(trace, selection.selected), asOf: trace.asOf })
}
