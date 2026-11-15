// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { buildComplianceTrace, type ComplianceTrace, type TraceInputs } from '../lib/compliance/trace'
import { COMPLIANCE_TOOLS, ToolInputError, buildCitationContext, callComplianceTool, checkCitations } from '../lib/compliance/tools'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
vi.mock('@/lib/compliance/trace-server', () => ({ loadComplianceTrace: vi.fn() }))
import { auth } from '@/auth'
import { loadComplianceTrace } from '@/lib/compliance/trace-server'
import { POST as mcpPost } from '@/app/api/compliance/mcp/route'
import { POST as askPost } from '@/app/api/compliance/ask/route'

const inputs: TraceInputs = {
  frameworks: [{ id: 'DORA', name: 'DORA' }],
  controls: [
    { id: 'audit-trail', title: 'Audit trail', category: 'x', frameworks: ['DORA'], references: [], evidence: 'e', declaredStatus: 'enforced', evidenceRefs: [{ kind: 'gate', ref: 'g1' }] },
    { id: 'fraud-monitoring', title: 'Fraud', category: 'x', frameworks: ['DORA'], references: [], evidence: 'e', declaredStatus: 'partial', evidenceRefs: [{ kind: 'adr', ref: '0084' }] },
  ],
  obligations: [
    { id: 'dora-major-ict-incident', area: 'ict', name: { cs: 'Incident', en: 'Incident' }, authority: 'cnb', legalBasis: 'DORA Art. 19', cadence: 'x', cadenceConfidence: 'verified', coverage: { status: 'bff-only' }, controls: ['audit-trail'] },
    { id: 'cnb-bop', area: 'statistics', name: { cs: 'BoP', en: 'BoP' }, authority: 'cnb', legalBasis: 'x', cadence: 'x', cadenceConfidence: 'known-eu', coverage: { status: 'filed-return' } },
  ],
  areas: [],
  gates: [{ id: 'g1', name: 'Gate one', mode: 'enforced', line: 1 }],
  commit: null,
  postureCollectedAt: null,
  adrSlugs: {},
  gateHealth: null,
  now: '2026-11-15T12:00:00.000Z',
}
const trace: ComplianceTrace = buildComplianceTrace(inputs)

describe('compliance MCP tools (ADR-0379 phase 3)', () => {
  it('declares only read tools with closed input schemas', () => {
    expect(COMPLIANCE_TOOLS.map(t => t.name)).toEqual(['compliance_list_obligations', 'compliance_get_trace', 'compliance_list_gaps', 'compliance_get_evidence'])
    expect(COMPLIANCE_TOOLS.every(t => t.inputSchema.additionalProperties === false)).toBe(true)
  })

  it('returns the computed verdict, not a re-derived one, and dates every result', () => {
    const r = callComplianceTool(trace, 'compliance_get_evidence', { controlId: 'fraud-monitoring' }) as Record<string, unknown>
    expect(r).toMatchObject({ verdict: 'unverified', downgradeReason: 'no-machine-evidence', ref: 'control:fraud-monitoring' })
    expect(r.asOf).toEqual(trace.asOf)
  })

  it('walks the chain from an obligation and from a control', () => {
    expect(callComplianceTool(trace, 'compliance_get_trace', { id: 'dora-major-ict-incident' })).toMatchObject({ controls: [{ id: 'audit-trail' }] })
    expect(callComplianceTool(trace, 'compliance_get_trace', { id: 'audit-trail' })).toMatchObject({ obligations: [{ id: 'dora-major-ict-incident' }] })
  })

  it('lists gaps and filters obligations', () => {
    expect(callComplianceTool(trace, 'compliance_list_gaps', {})).toMatchObject({ unverifiedControls: [{ id: 'fraud-monitoring' }], uncoveredObligations: [{ id: 'dora-major-ict-incident' }] })
    expect(callComplianceTool(trace, 'compliance_list_obligations', { area: 'statistics' })).toMatchObject({ count: 1, obligations: [{ id: 'cnb-bop' }] })
  })

  it('rejects unknown tools, unknown arguments, missing ids and oversized input', () => {
    expect(() => callComplianceTool(trace, 'compliance_set_status', {})).toThrow(ToolInputError)
    expect(() => callComplianceTool(trace, 'compliance_get_evidence', { controlId: 'audit-trail', verdict: 'enforced' })).toThrow(/unknown argument/)
    expect(() => callComplianceTool(trace, 'compliance_get_trace', {})).toThrow(/required/)
    expect(() => callComplianceTool(trace, 'compliance_get_trace', { id: 'x'.repeat(201) })).toThrow(/at most 200/)
    expect(() => callComplianceTool(trace, 'compliance_get_trace', { id: 'nope' })).toThrow(/no obligation or control/)
  })
})

describe('citation contract', () => {
  it('accepts an answer whose every citation resolves', () => {
    expect(checkCitations('Audit is enforced [[control:audit-trail]] for [[obligation:dora-major-ict-incident]].', trace))
      .toEqual({ ok: true, cited: ['control:audit-trail', 'obligation:dora-major-ict-incident'], unknown: [] })
  })
  it('rejects an uncited answer', () => {
    expect(checkCitations('Everything is compliant.', trace).ok).toBe(false)
  })
  it('rejects an answer that cites a row the trace does not have, even next to a real one', () => {
    expect(checkCitations('See [[control:audit-trail]] and [[control:made-up]].', trace)).toMatchObject({ ok: false, unknown: ['control:made-up'] })
  })
  it('puts every row and its computed verdict in the model context', () => {
    const ctx = buildCitationContext(trace)
    expect(ctx).toContain('[[control:fraud-monitoring]] Fraud | declared=partial verdict=unverified reason=no-machine-evidence')
    expect(ctx).toContain('[[obligation:cnb-bop]]')
    expect(ctx).toContain('never change, upgrade or assess a verdict')
  })
})

describe('compliance MCP and ask routes', () => {
  const rpc = (body: unknown) => new Request('http://localhost/api/compliance/mcp', { method: 'POST', body: JSON.stringify(body) })
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'staff-token' } } as never)
    vi.mocked(loadComplianceTrace).mockResolvedValue({ status: 'available', missing: [], ...trace })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('refuses an anonymous caller on both routes', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    expect((await mcpPost(rpc({ jsonrpc: '2.0', id: 1, method: 'tools/list' }))).status).toBe(401)
    expect((await askPost(new Request('http://localhost/api/compliance/ask', { method: 'POST', body: '{"question":"x"}' }))).status).toBe(401)
  })

  it('speaks JSON-RPC: tools/list, tools/call, and errors for unknown methods', async () => {
    const list = await (await mcpPost(rpc({ jsonrpc: '2.0', id: 1, method: 'tools/list' }))).json()
    expect(list.result.tools).toHaveLength(4)
    const call = await (await mcpPost(rpc({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'compliance_list_gaps', arguments: {} } }))).json()
    expect(call.result.structuredContent.unverifiedControls[0].id).toBe('fraud-monitoring')
    const bad = await (await mcpPost(rpc({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'compliance_set_status', arguments: {} } }))).json()
    expect(bad.result.isError).toBe(true)
    const missing = await (await mcpPost(rpc({ jsonrpc: '2.0', id: 4, method: 'resources/write' }))).json()
    expect(missing.error.code).toBe(-32601)
  })

  it('renders a cited answer and withholds an uncited one', async () => {
    const relay = vi.fn()
    vi.stubGlobal('fetch', relay)
    const ask = () => askPost(new Request('http://localhost/api/compliance/ask', { method: 'POST', body: JSON.stringify({ question: 'Is audit enforced?' }) }))

    relay.mockResolvedValueOnce(Response.json({ reply: 'Yes [[control:audit-trail]].' }))
    const ok = await ask()
    expect(ok.status).toBe(200)
    expect(await ok.json()).toMatchObject({ citations: ['control:audit-trail'] })
    const sent = JSON.parse(relay.mock.calls[0][1].body as string)
    expect(sent).toMatchObject({ allowTools: false })
    expect(sent.context).toContain('[[control:audit-trail]]')

    relay.mockResolvedValueOnce(Response.json({ reply: 'All compliant.' }))
    const uncited = await ask()
    expect(uncited.status).toBe(422)
    expect(await uncited.json()).toMatchObject({ error: 'uncited_answer' })
  })
})
