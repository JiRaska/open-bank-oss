// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mkdtempSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'

const dirs: string[] = []
afterEach(() => {
  delete process.env.OPENBANK_TEST_INTELLIGENCE
  delete process.env.PROMETHEUS_URL
  vi.unstubAllGlobals()
  dirs.splice(0).forEach(dir => rmSync(dir, { recursive: true, force: true }))
})

describe('GET /api/test-intelligence', () => {
  it('serves a valid versioned snapshot without changing its evidence states', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-route-'))
    dirs.push(dir)
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-22T00:00:00.000Z',
      components: [{ component: 'openbank-ledger-service', released: true, moneyPath: true, evidence: [], coverage: { state: 'not-run' } }],
      contracts: [], mutations: [], performance: [], syntheticJourneys: [], history: [],
      totals: { components: 1, componentsWithExecutionEvidence: 0, moneyPathComponents: 1, failingEvidence: 0, missingEvidence: 1, staleEvidence: 0 },
      warnings: [],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.schemaVersion).toBe(1)
    expect(body.components[0].coverage.state).toBe('not-run')
    expect(body.totals.missingEvidence).toBe(1)
  })

  it('returns explicit unavailable evidence when the bundle is absent', async () => {
    process.env.OPENBANK_TEST_INTELLIGENCE = '/does/not/exist.json'
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.components).toEqual([])
    expect(body.warnings[0]).toMatch(/not bundled/i)
  })

  it('keeps mobile CI separate and attaches consent-gated RUM arrival evidence', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-rum-route-'))
    dirs.push(dir)
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-23T00:00:00.000Z', components: [], contracts: [], mutations: [], performance: [], syntheticJourneys: [], history: [], runHistory: [], testCases: [],
      clientExperiences: [{ id: 'openbank-app', title: 'OpenBank customer app', surface: 'mobile', platforms: ['android', 'ios'], evidence: [], rum: { state: 'unknown', policy: 'consent-gated', detail: 'static capability', observedAt: null }, blocker: null }],
      totals: { components: 0, componentsWithExecutionEvidence: 0, moneyPathComponents: 0, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 }, warnings: [],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
    process.env.PROMETHEUS_URL = 'http://prometheus.test'
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ status: 'success', data: { result: [{ value: [1, '12'] }] } }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ status: 'success', data: { result: [{ value: [1, '2'] }] } }) }))
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.clientExperiences[0].rum).toMatchObject({
      state: 'passed', policy: 'consent-gated', source: 'prometheus', sampledSpansLast7d: 12, errorSpansLast7d: 2,
    })
    expect(body.clientExperiences[0].evidence).toEqual([])
  })
})
