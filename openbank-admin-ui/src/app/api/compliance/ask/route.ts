// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Cited question answering over the compliance trace (ADR-0379 phase 3). The question goes to
// agent-service's governed chat (ADR-0031) with tool execution disabled and the trace rows as
// context. The answer is returned only if it cites at least one trace row and every citation
// resolves; otherwise the caller gets 422 and nothing is rendered. The model can explain the
// trace but has no tool, no write path and no way to change a verdict.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { loadComplianceTrace } from '@/lib/compliance/trace-server'
import { buildCitationContext, checkCitations } from '@/lib/compliance/tools'

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
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 30000)
    const res = await fetch(`${agentBase()}/agent/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` },
      body: JSON.stringify({
        messages: [{ role: 'user', content: question.trim() }],
        context: buildCitationContext(trace),
        allowTools: false,
      }),
      signal: ctrl.signal,
      cache: 'no-store',
    })
    clearTimeout(timer)
    if (!res.ok) return NextResponse.json({ error: 'agent_unreachable' }, { status: 502 })
    reply = String(((await res.json()) as { reply?: unknown }).reply ?? '')
  } catch {
    return NextResponse.json({ error: 'agent_unreachable' }, { status: 502 })
  }

  const citations = checkCitations(reply, trace)
  if (!citations.ok) {
    return NextResponse.json({ error: 'uncited_answer', unknown: citations.unknown }, { status: 422 })
  }
  return NextResponse.json({ answer: reply, citations: citations.cited, asOf: trace.asOf })
}
