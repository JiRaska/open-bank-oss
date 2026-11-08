// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { parse } from 'yaml'
import { workspaceCase, workspaceComponent, workspaceReport, workspaceObservedAt } from '../../e2e/fixtures/test-intelligence-workspace'
import { decodeTestReadReply, parseTestReadRequest, selectTestReadView, testReadUrl, type TestReadRequest } from '@/lib/test-intelligence-delivery'

const project = (request: TestReadRequest, report = workspaceReport()) => {
  const result = selectTestReadView(report, request)
  if (!result.ok) throw new Error(result.error)
  return result.value
}
describe('bounded Test Intelligence delivery', () => {
  it('documents the additive read views and pagination bounds in the OpenAPI contract', () => {
    const api = parse(readFileSync('docs/admin-ui/test-intelligence.openapi.yaml', 'utf8'))
    const operation = api.paths['/api/test-intelligence'].get
    expect(operation.parameters.find((item: { name: string }) => item.name === 'view').schema).toMatchObject({ default: 'full', enum: ['full', 'overview', 'component', 'case'] })
    expect(operation.parameters.find((item: { name: string }) => item.name === 'limit').schema.maximum).toBe(25)
    expect(operation.responses['409']).toBeDefined()
    expect(operation.security).toEqual([{ adminSession: [] }])
  })
  it('keeps the legacy archive lossless and reduces a 2000-test archive to a bounded matrix', () => {
    const report = workspaceReport({ components: [workspaceComponent, ...Array.from({ length: 73 }, (_, index) => ({ ...workspaceComponent, component: `openbank-fixture-${index}` }))], testCases: Array.from({ length: 2000 }, (_, index) => ({ ...workspaceCase, fingerprint: `case-${index}`, name: `${workspaceCase.name} ${index}`, recentRuns: Array.from({ length: 10 }, () => ({ run: workspaceCase.lastRun!, state: 'passed' as const, durationMs: 12 })) })) })
    expect(project({ view: 'full' }, report)).toBe(report)
    const overview = project({ view: 'overview' }, report)
    expect(JSON.stringify(overview)).not.toContain('recentRuns')
    expect(JSON.stringify(overview)).not.toContain('testCases')
    expect(Buffer.byteLength(JSON.stringify(overview))).toBeLessThan(64 * 1024)
    expect(Buffer.byteLength(JSON.stringify(overview))).toBeLessThan(Buffer.byteLength(JSON.stringify(report)) / 50)
    const page = project({ view: 'component', component: workspaceComponent.component, kind: '', query: '', offset: 25, limit: 25, collectedAt: workspaceObservedAt }, report)
    expect('tests' in page && page.tests).toHaveLength(25)
    expect('total' in page && page.total).toBe(2000)
    expect(JSON.stringify(page)).not.toContain('recentRuns')
    expect(Buffer.byteLength(JSON.stringify(page))).toBeLessThan(16 * 1024)
  })
  it('retains the exact individual source, run, and outcome in a single detail', () => {
    const detail = project({ view: 'case', component: workspaceCase.component, kind: workspaceCase.kind, fingerprint: workspaceCase.fingerprint, collectedAt: workspaceObservedAt })
    expect('testCase' in detail && detail.testCase).toBe(workspaceCase)
  })
  it('filters before paging and clamps an out-of-range page without hiding retained results', () => {
    const page = project({ view: 'component', component: workspaceCase.component, kind: 'unit', query: 'DUPLICATE', offset: 1000, limit: 25 })
    expect('tests' in page && page.tests).toHaveLength(1)
    expect('offset' in page && page.offset).toBe(0)
    expect('tests' in page && page.tests[0]).not.toHaveProperty('lastRun')
  })
  it('rejects cross-snapshot details and ambiguous identities instead of mixing evidence', () => {
    const request: TestReadRequest = { view: 'case', component: workspaceCase.component, kind: 'unit', fingerprint: workspaceCase.fingerprint, collectedAt: '2026-11-07T00:00:00Z' }
    expect(selectTestReadView(workspaceReport(), request)).toMatchObject({ ok: false, status: 409, error: 'evidence_changed' })
    expect(selectTestReadView(workspaceReport({ testCases: [workspaceCase, workspaceCase] }), { ...request, collectedAt: workspaceObservedAt })).toMatchObject({ ok: false, status: 404 })
  })
  it('supports an old server returning the archive while preserving identical view semantics', () => {
    const request: TestReadRequest = { view: 'component', component: workspaceCase.component, kind: 'unit', query: '', offset: 0, limit: 25, collectedAt: workspaceObservedAt }
    expect(decodeTestReadReply(workspaceReport(), request)).toEqual(selectTestReadView(workspaceReport(), request))
    const overview = project({ view: 'overview' })
    expect(decodeTestReadReply(overview, { view: 'overview' })).toEqual({ ok: true, value: overview })
  })
  it.each(['view=unknown', 'view=component&component=../secret', 'view=component&component=openbank-account-service&limit=26', 'view=component&component=openbank-account-service&offset=-1', 'view=case&component=openbank-account-service&fingerprint=../case', 'view=component&component=openbank-account-service&kind=invalid'])('rejects invalid query %s', query => {
    expect(parseTestReadRequest(new URLSearchParams(query))).toBeNull()
  })
  it('round trips the encoded search and timestamp and preserves the default full contract', () => {
    const request: TestReadRequest = { view: 'component', component: workspaceCase.component, kind: 'unit', query: 'a & b', offset: 25, limit: 25, collectedAt: workspaceObservedAt }
    expect(parseTestReadRequest(new URL(testReadUrl(request), 'https://example.org').searchParams)).toEqual(request)
    expect(parseTestReadRequest(new URLSearchParams())).toEqual({ view: 'full' })
  })
})
