// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mkdtempSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'

const dirs: string[] = []
afterEach(() => {
  delete process.env.OPENBANK_TEST_INTELLIGENCE
  delete process.env.PROMETHEUS_URL
  delete process.env.TEMPO_URL
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
    process.env.TEMPO_URL = 'http://tempo.test'
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL) => {
      const url = new URL(String(input))
      if (url.hostname === 'tempo.test') {
        expect(url.pathname).toBe('/api/search')
        expect(url.searchParams.get('tags')).toBe('service.name="openbank-app"')
        expect(url.searchParams.get('limit')).toBe('1000')
        return new Response(JSON.stringify({ traces: Array.from({ length: 12 }, (_, index) => ({ traceID: `trace-${index}` })) }), { status: 200 })
      }
      const query = url.searchParams.get('query') ?? ''
      const value = query.includes('STATUS_CODE_ERROR') ? '2' : '1'
      return new Response(JSON.stringify({ status: 'success', data: { result: [{ value: [1, value] }] } }), { status: 200 })
    }))
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.clientExperiences[0].rum).toMatchObject({
      state: 'passed', policy: 'consent-gated', source: 'tempo', sampledSpansLast7d: 12, errorSpansLast7d: 2,
    })
    expect(body.clientExperiences[0].rum.detail).toContain('12 sampled mobile RUM trace(s)')
    expect(body.clientExperiences[0].evidence).toEqual([])
  })

  it('does not treat a retained historical failed Job as a current synthetic failure', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-synthetic-route-'))
    dirs.push(dir)
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-25T00:00:00.000Z', components: [], contracts: [], mutations: [], performance: [], history: [], runHistory: [], testCases: [], clientExperiences: [],
      syntheticJourneys: [{ id: 'public-edge', title: 'Public edge', status: 'active', state: 'unknown', severity: 'page', schedule: '*/5 * * * *', environment: 'sandbox', covers: [], falsifies: 'Break the edge.', blocker: null }],
      totals: { components: 0, componentsWithExecutionEvidence: 0, moneyPathComponents: 0, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 }, warnings: [],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
    process.env.PROMETHEUS_URL = 'http://prometheus.test'
    const now = Date.now() / 1000
    const queries: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL) => {
      const query = new URL(String(input)).searchParams.get('query') ?? ''
      queries.push(query)
      const payload = query.includes('kube_job_status_completion_time')
        ? { status: 'success', data: { result: [] } }
        : query.includes('kube_cronjob_status_last_successful_time') || query.includes('kube_cronjob_status_last_schedule_time')
          ? { status: 'success', data: { result: [{ value: [now, String(now)] }] } }
          : { status: 'success', data: { result: [] } }
      return new Response(JSON.stringify(payload), { status: 200 })
    }))
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.syntheticJourneys[0].state).toBe('passed')
    expect(queries.some(query => query.includes('kube_job_status_completion_time'))).toBe(true)
    expect(queries.some(query => query.includes('max_over_time(kube_job_status_failed'))).toBe(false)
  })
})
