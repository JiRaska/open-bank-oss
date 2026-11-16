// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { createHash } from 'node:crypto'
import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import { keyExplanationFacts, keyExplanationPrompt, keyExplanationRequest } from '@/lib/security/keys/explain'
import { measure, prometheusBase } from '@/lib/security/keys/prometheus'
import { allStatuses } from '@/lib/security/keys/status'

// Tool-free key custodian explanation (#16292). Mirrors /api/test-intelligence/explain:
// session + permission, a tiny strictly-parsed body, facts built server-side from the CURRENT
// measurement, capability negotiation before any data leaves, and a reply accepted only when
// agent-service confirms tools were restricted and none were called.

export const dynamic = 'force-dynamic'
const MAX_BODY = 512
const cache = new Map<string, { expires: number; value: { explanation: string; model: string; entryId: string } }>()
const unavailable = () => NextResponse.json({ error: 'explanation_unavailable' }, { status: 503, headers: { 'Cache-Control': 'no-store' } })

function agentBase(): string {
  return process.env.SERVICES_HOST === 'container'
    ? 'http://openbank-agent-service:8109'
    : process.env.AGENT_SERVICE_URL?.replace(/\/mcp$/, '') ?? 'http://localhost:8109'
}

export async function POST(request: Request): Promise<Response> {
  const session = await auth()
  const token = session?.user?.accessToken
  if (!token) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  const roles = session.user.roles ?? []
  // keys:view to see the entry, agent:view because agent-service accepts only those roles:
  // without both the call would spend budget or be refused upstream.
  if (!hasPermission(roles, 'keys:view') || !hasPermission(roles, 'agent:view')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }
  if (Number(request.headers.get('content-length')) > MAX_BODY) return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
  let input
  try {
    const text = await request.text()
    if (text.length > MAX_BODY) return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
    input = keyExplanationRequest.safeParse(JSON.parse(text))
  } catch { return NextResponse.json({ error: 'invalid_request' }, { status: 400 }) }
  if (!input.success) return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
  const { entryId, language } = input.data

  const m = await measure(prometheusBase())
  const status = allStatuses(m.certs, Math.floor(Date.now() / 1000)).find((s) => s.id === entryId)
  const facts = status ? keyExplanationFacts(entryId, status, language) : null
  if (!facts) return unavailable()

  const key = createHash('sha256').update(token).update(JSON.stringify({ facts, language })).digest('hex')
  const saved = cache.get(key)
  if (saved && saved.expires > Date.now()) return NextResponse.json(saved.value, { headers: { 'Cache-Control': 'no-store' } })

  const base = agentBase()
  const signal = AbortSignal.any([request.signal, AbortSignal.timeout(35_000)])
  try {
    const capability = await fetch(`${base}/agent/models`, { headers: { Authorization: `Bearer ${token}` }, signal, cache: 'no-store' })
    if (!capability.ok) return unavailable()
    const models = await capability.json()
    if (models.capabilities?.toolFreeChat !== true) return unavailable()
    const response = await fetch(`${base}/agent/chat`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, signal, cache: 'no-store',
      body: JSON.stringify({ allowTools: false, context: 'Read-only explanation of a key and certificate inventory entry.', messages: [{ role: 'user', content: keyExplanationPrompt(facts, language) }] }),
    })
    if (!response.ok) return unavailable()
    const result = await response.json()
    if (result.toolsRestricted !== true || !Array.isArray(result.toolCalls) || result.toolCalls.length !== 0
      || typeof result.reply !== 'string' || !result.reply.trim() || typeof result.model !== 'string' || result.model === 'mock-echo') return unavailable()
    const value = { explanation: result.reply.slice(0, 6000), model: result.model.slice(0, 120), entryId }
    if (cache.size >= 100) cache.delete(cache.keys().next().value!)
    cache.set(key, { expires: Date.now() + 5 * 60_000, value })
    return NextResponse.json(value, { headers: { 'Cache-Control': 'no-store' } })
  } catch { return unavailable() }
}
