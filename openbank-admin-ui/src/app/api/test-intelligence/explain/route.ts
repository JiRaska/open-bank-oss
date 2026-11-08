// SPDX-License-Identifier: Apache-2.0
import { createHash } from 'node:crypto'
import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import { readTestExplanationSource } from '@/lib/evidence/testExplanationSource'
import { explanationPrompt, explanationRequest, testExplanationFacts } from '@/lib/test-explanation'

export const dynamic = 'force-dynamic'
const cache = new Map<string, { expires: number; value: { explanation: string; model: string; fingerprint: string; collectedAt: string } }>()
const unavailable = () => NextResponse.json({ error: 'explanation_unavailable' }, { status: 503, headers: { 'Cache-Control': 'no-store' } })

export async function POST(request: Request): Promise<Response> {
  const session = await auth()
  const token = session?.user?.accessToken
  if (!token) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  // Same permission as the /system/tests page. Without it any signed-in role could
  // trigger an AI explanation (and spend the LLM budget) for evidence it cannot view.
  if (!hasPermission(session.user.roles ?? [], 'system:view')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }
  if (Number(request.headers.get('content-length')) > 2048) return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
  let input
  try {
    const reader = request.body?.getReader()
    if (!reader) return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
    const chunks: Uint8Array[] = []
    let size = 0
    while (true) {
      const chunk = await reader.read()
      if (chunk.done) break
      size += chunk.value.byteLength
      if (size > 2048) { await reader.cancel(); return NextResponse.json({ error: 'invalid_request' }, { status: 400 }) }
      chunks.push(chunk.value)
    }
    const text = Buffer.concat(chunks).toString('utf8')
    if (text.length > 2048) return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
    input = explanationRequest.safeParse(JSON.parse(text))
  } catch { return NextResponse.json({ error: 'invalid_request' }, { status: 400 }) }
  if (!input.success) return NextResponse.json({ error: 'invalid_request' }, { status: 400 })
  const { fingerprint, collectedAt, language } = input.data
  const report = await readTestExplanationSource()
  if (!report) return unavailable()
  if (report.collectedAt !== collectedAt) return NextResponse.json({ error: 'evidence_changed' }, { status: 409 })
  const matches = report.testCases.filter(item => item?.fingerprint === fingerprint)
  if (matches.length !== 1) return NextResponse.json({ error: 'test_unavailable' }, { status: 404 })
  const facts = testExplanationFacts(matches[0])
  if (!facts) return unavailable()
  const key = createHash('sha256').update(token).update(JSON.stringify({ facts, language, collectedAt })).digest('hex')
  const saved = cache.get(key)
  if (saved && saved.expires > Date.now()) return NextResponse.json(saved.value, { headers: { 'Cache-Control': 'no-store' } })
  const base = process.env.SERVICES_HOST === 'container'
    ? 'http://openbank-agent-service:8109'
    : process.env.AGENT_SERVICE_URL?.replace(/\/mcp$/, '') ?? 'http://localhost:8109'
  const signal = AbortSignal.any([request.signal, AbortSignal.timeout(35_000)])
  try {
    const capability = await fetch(`${base}/agent/models`, { headers: { Authorization: `Bearer ${token}` }, signal, cache: 'no-store' })
    if (!capability.ok) return unavailable()
    const models = await capability.json()
    // An older service ignores unknown request fields. Negotiate before sending any test metadata.
    if (models.capabilities?.toolFreeChat !== true) return unavailable()
    const response = await fetch(`${base}/agent/chat`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, signal, cache: 'no-store',
      body: JSON.stringify({ allowTools: false, context: 'Read-only explanation of retained test evidence.', messages: [{ role: 'user', content: explanationPrompt(facts, language) }] }),
    })
    if (!response.ok) return unavailable()
    const result = await response.json()
    if (result.toolsRestricted !== true || !Array.isArray(result.toolCalls) || result.toolCalls.length !== 0
      || typeof result.reply !== 'string' || !result.reply.trim() || typeof result.model !== 'string' || result.model === 'mock-echo') return unavailable()
    const value = { explanation: result.reply.slice(0, 8000), model: result.model.slice(0, 120), fingerprint, collectedAt }
    if (cache.size >= 100) cache.delete(cache.keys().next().value!)
    cache.set(key, { expires: Date.now() + 5 * 60_000, value })
    return NextResponse.json(value, { headers: { 'Cache-Control': 'no-store' } })
  } catch { return unavailable() }
}
