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
  delete process.env.OPENBANK_TEST_INTELLIGENCE_STALE_AFTER_DAYS
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
    expect(body.performanceHistory).toEqual([])
    expect(body.testImpact).toMatchObject({
      mode: 'shadow', mappingState: 'unknown', selectionState: 'unavailable', declaredByAllRetainedRuns: false,
    })
  })

  it('returns explicit unavailable evidence when the bundle is absent', async () => {
    process.env.OPENBANK_TEST_INTELLIGENCE = '/does/not/exist.json'
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.components).toEqual([])
    expect(body.warnings[0]).toMatch(/not bundled/i)
  })

  it('ages a deployed successful snapshot at request time while preserving failures and recomputing totals', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-runtime-freshness-'))
    dirs.push(dir)
    const file = path.join(dir, 'report.json')
    const evidence = (kind: 'unit' | 'integration' | 'trace', state: 'passed' | 'failed', observedAt = '2020-01-01T00:00:00.000Z') => ({
      kind, state, observedAt, source: 'run:v1', environment: 'ci',
    })
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2020-01-01T00:00:00.000Z',
      components: [{
        component: 'openbank-ledger-service', released: true, moneyPath: true,
        evidence: [evidence('unit', 'passed'), evidence('integration', 'failed'), evidence('trace', 'passed', '2999-01-01T00:00:00.000Z')],
        coverage: { state: 'passed', observedAt: '2020-01-01T00:00:00.000Z', source: 'kover', lines: { covered: 1, missed: 0, percentage: 100 }, branches: { covered: 1, missed: 0, percentage: 100 } },
        testInfrastructure: { declared: [], observed: [] },
      }],
      contracts: [{ consumer: 'openbank-ledger-service', provider: 'openbank-balance-service', pactFile: 'ledger-balance.json', state: 'passed', observedAt: '2020-01-01T00:00:00.000Z', interactions: 1 }],
      mutations: [{ component: 'openbank-ledger-service', state: 'passed', observedAt: '2020-01-01T00:00:00.000Z', total: 1, killed: 1, survived: 0, noCoverage: 0, score: 100 }],
      performance: [{ id: 'old-perf', component: 'openbank-ledger-service', state: 'passed', observedAt: '2020-01-01T00:00:00.000Z', source: 'perf.js', thresholds: 1 }],
      syntheticJourneys: [{ id: 'edge', title: 'Edge', status: 'active', state: 'unknown', severity: 'page', schedule: '*/5 * * * *', environment: 'sandbox', covers: [], falsifies: 'Break it', blocker: null, ci: { state: 'passed', observedAt: '2020-01-01T00:00:00.000Z', detail: 'old pass', run: { id: '1', attempt: 1, commit: 'abc', branch: 'main', workflow: 'Synthetic', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/1' } } }],
      clientExperiences: [{ id: 'openbank-app', title: 'App', surface: 'mobile', platforms: ['android'], evidence: [evidence('unit', 'passed')], rum: { state: 'not-run', policy: 'consent-gated', detail: 'none', observedAt: null }, blocker: null }],
      history: [], runHistory: [], testCases: [],
      totals: { components: 1, componentsWithExecutionEvidence: 1, moneyPathComponents: 1, failingEvidence: 99, missingEvidence: 99, staleEvidence: 0 }, warnings: [],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
    process.env.OPENBANK_TEST_INTELLIGENCE_STALE_AFTER_DAYS = '1'
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()

    expect(body.components[0].evidence).toEqual(expect.arrayContaining([
      expect.objectContaining({ kind: 'unit', state: 'stale' }),
      expect.objectContaining({ kind: 'integration', state: 'failed' }),
      expect.objectContaining({ kind: 'trace', state: 'unknown' }),
    ]))
    expect(body.components[0].coverage.state).toBe('stale')
    expect(body.contracts[0].state).toBe('stale')
    expect(body.mutations[0].state).toBe('stale')
    expect(body.performance[0].state).toBe('stale')
    expect(body.syntheticJourneys[0].ci.state).toBe('stale')
    expect(body.clientExperiences[0].evidence[0].state).toBe('stale')
    expect(body.totals).toMatchObject({ failingEvidence: 1, missingEvidence: 0, staleEvidence: 1, unknownEvidence: 1, unresolvedEvidence: 1 })
  })

  it('keeps mobile CI separate and attaches consent-gated RUM arrival evidence', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-rum-route-'))
    dirs.push(dir)
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-23T00:00:00.000Z', components: [], contracts: [], mutations: [], performance: [], syntheticJourneys: [], history: [], runHistory: [], testCases: [],
      clientExperiences: [{ id: 'openbank-app', title: 'OpenBank customer app', surface: 'mobile', platforms: ['android', 'ios'], evidence: [], rum: {
        state: 'unknown', policy: 'consent-gated', detail: 'static capability', observedAt: null,
        platforms: [
          { platform: 'android', capability: 'passed', runtime: 'unknown', detail: 'generic arrival is not Android proof' },
          { platform: 'ios', capability: 'passed', runtime: 'unknown', detail: 'generic arrival is not iOS proof' },
        ],
      }, blocker: null }],
      totals: { components: 0, componentsWithExecutionEvidence: 0, moneyPathComponents: 0, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 }, warnings: [],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
    process.env.PROMETHEUS_URL = 'http://prometheus.test'
    process.env.TEMPO_URL = 'http://tempo.test'
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL) => {
      const url = new URL(String(input))
      if (url.hostname === 'tempo.test') {
        if (url.pathname === '/api/search') {
          expect(url.searchParams.get('tags')).toBe('service.name="openbank-app"')
          expect(url.searchParams.get('limit')).toBe('1000')
          return new Response(JSON.stringify({ traces: Array.from({ length: 12 }, (_, index) => ({ traceID: `trace-${index}` })) }), { status: 200 })
        }
        if (url.pathname === '/api/v2/search/tag/.os.type/values') {
          return new Response(JSON.stringify({ tagValues: [{ value: 'ios' }, { value: 'linux' }] }), { status: 200 })
        }
        return new Response(JSON.stringify({ batches: [] }), { status: 200 })
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
    expect(body.clientExperiences[0].rum.platforms).toEqual([
      expect.objectContaining({ platform: 'android', capability: 'passed', runtime: 'not-run' }),
      expect.objectContaining({ platform: 'ios', capability: 'passed', runtime: 'passed' }),
    ])
    expect(body.clientExperiences[0].rum.backendCorrelations).toEqual({ inspectedTraces: 12, correlatedTraces: 0, backendServices: [], truncated: false })
    expect(body.clientExperiences[0].evidence).toEqual([])
  })

  it('reports a bounded mobile-to-backend trace correlation without promoting it to a test verdict', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-rum-correlation-'))
    dirs.push(dir)
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-26T00:00:00.000Z', components: [], contracts: [], mutations: [], performance: [], syntheticJourneys: [], history: [], runHistory: [], testCases: [],
      clientExperiences: [{ id: 'openbank-app', title: 'OpenBank customer app', surface: 'mobile', platforms: ['android'], evidence: [], rum: { state: 'unknown', policy: 'consent-gated', detail: 'static capability', observedAt: null }, blocker: null }],
      totals: { components: 0, componentsWithExecutionEvidence: 0, moneyPathComponents: 0, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 }, warnings: [],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
    process.env.TEMPO_URL = 'http://tempo.test'
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL) => {
      const url = new URL(String(input))
      if (url.pathname === '/api/search') return new Response(JSON.stringify({ traces: [{ traceID: 'mobile-only' }, { traceID: 'mobile-backend' }] }), { status: 200 })
      if (url.pathname.endsWith('/mobile-only')) return new Response(JSON.stringify({ batches: [{ resource: { attributes: [{ key: 'service.name', value: { stringValue: 'openbank-app' } }] } }] }), { status: 200 })
      return new Response(JSON.stringify({ batches: [{ resource: { attributes: [{ key: 'service.name', value: { stringValue: 'openbank-app' } }] } }, { resource: { attributes: [{ key: 'service.name', value: { stringValue: 'openbank-copilot-service' } }] } }] }), { status: 200 })
    }))
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.clientExperiences[0].rum).toMatchObject({ state: 'passed', backendCorrelations: { inspectedTraces: 2, correlatedTraces: 1, backendServices: ['openbank-copilot-service'], truncated: false } })
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
      const payload = query.includes('kube_job_status_failed')
        ? { status: 'success', data: { result: [{ value: [now, '0'] }] } }
        : query.includes('kube_job_status_completion_time')
        ? { status: 'success', data: { result: [] } }
        : query.includes('kube_cronjob_status_last_successful_time') || query.includes('kube_cronjob_status_last_schedule_time')
          ? { status: 'success', data: { result: [{ value: [now, String(now)] }] } }
          : { status: 'success', data: { result: [] } }
      return new Response(JSON.stringify(payload), { status: 200 })
    }))
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.syntheticJourneys[0].state).toBe('passed')
    expect(body.syntheticJourneys[0].live.failuresWithinWindow).toBe(0)
    expect(queries.some(query => query.includes('kube_job_status_completion_time'))).toBe(true)
    expect(queries.some(query => query.includes('< 900'))).toBe(true)
    expect(queries.some(query => query.includes('kube_job_status_failed') && query.includes('or vector(0)'))).toBe(true)
    expect(queries.some(query => query.includes('max_over_time(kube_job_status_failed'))).toBe(false)
  })

  it('uses Kubernetes completion time, not the Prometheus scrape time, for recent synthetic runs', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-synthetic-run-history-'))
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
    const completed = Math.floor(now - 120)
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL) => {
      const query = new URL(String(input)).searchParams.get('query') ?? ''
      const payload = query.includes('openbank_evidence_state')
        ? { status: 'success', data: { result: [{ metric: { job_name: 'journey-public-edge-123', openbank_evidence_state: 'passed' }, value: [now, String(completed)] }] } }
        : query.includes('kube_job_status_completion_time')
          ? { status: 'success', data: { result: [] } }
          : { status: 'success', data: { result: [{ value: [now, String(now)] }] } }
      return new Response(JSON.stringify(payload), { status: 200 })
    }))
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.syntheticJourneys[0].live.recentRuns).toEqual([
      { id: 'journey-public-edge-123', state: 'passed', observedAt: new Date(completed * 1000).toISOString() },
    ])
  })

  it('projects k6 remote-write performance as bounded supplementary evidence', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-synthetic-performance-'))
    dirs.push(dir)
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-26T00:00:00.000Z', components: [], contracts: [], mutations: [], performance: [], history: [], runHistory: [], testCases: [], clientExperiences: [],
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
      const value = query.includes('k6_http_req_duration_p95') ? '1834.8'
        : query.includes('k6_checks_rate') ? '0.997'
          : query.includes('kube_job_status_completion_time') ? undefined : String(now)
      return new Response(JSON.stringify({ status: 'success', data: { result: value === undefined ? [] : [{ value: [now, value] }] } }), { status: 200 })
    }))
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.syntheticJourneys[0]).toMatchObject({
      state: 'passed',
      live: { performance: { source: 'prometheus', windowSeconds: 900, worstP95Ms: 1834.8, worstCheckPassRatePercent: 99.7 } },
    })
    expect(body.performance).toEqual(expect.arrayContaining([expect.objectContaining({
      id: 'synthetic-public-edge', state: 'passed', source: 'runtime-synthetic:public-edge', thresholds: 0,
      metrics: { p95Ms: 1834.8, errorRatePercent: null, checkPassRatePercent: 99.7, requests: null },
    })]))
    expect(queries.some(query => query.includes('max_over_time(k6_http_req_duration_p95{journey="public-edge"}[900s])'))).toBe(true)
    expect(queries.some(query => query.includes('min_over_time(k6_checks_rate{journey="public-edge"}[900s])'))).toBe(true)
  })

  it('keeps an unavailable k6 metric explicit rather than converting it into performance success', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-synthetic-performance-absent-'))
    dirs.push(dir)
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-26T00:00:00.000Z', components: [], contracts: [], mutations: [], performance: [], history: [], runHistory: [], testCases: [], clientExperiences: [],
      syntheticJourneys: [{ id: 'public-edge', title: 'Public edge', status: 'active', state: 'unknown', severity: 'page', schedule: '*/5 * * * *', environment: 'sandbox', covers: [], falsifies: 'Break the edge.', blocker: null }],
      totals: { components: 0, componentsWithExecutionEvidence: 0, moneyPathComponents: 0, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 }, warnings: [],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
    process.env.PROMETHEUS_URL = 'http://prometheus.test'
    const now = Date.now() / 1000
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL) => {
      const query = new URL(String(input)).searchParams.get('query') ?? ''
      const result = query.includes('k6_http_req_duration_p95') || query.includes('k6_checks_rate')
        ? []
        : query.includes('kube_job_status_failed')
          ? [{ value: [now, '0'] }]
          : [{ value: [now, String(now)] }]
      return new Response(JSON.stringify({ status: 'success', data: { result } }), { status: 200 })
    }))
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.syntheticJourneys[0]).toMatchObject({
      state: 'passed',
      live: { performance: { worstP95Ms: null, worstCheckPassRatePercent: null } },
    })
    expect(body.performance).toEqual([])
  })

  it('shows a first scheduled Job still running as unresolved instead of claiming it was not run', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-synthetic-running-'))
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
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL) => {
      const query = new URL(String(input)).searchParams.get('query') ?? ''
      const payload = query.includes('kube_job_status_active')
        ? { status: 'success', data: { result: [{ value: [now, '1'] }] } }
        : query.includes('kube_job_status_completion_time')
          ? { status: 'success', data: { result: [] } }
          : { status: 'success', data: { result: [] } }
      return new Response(JSON.stringify(payload), { status: 200 })
    }))
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET()).json()
    expect(body.syntheticJourneys[0]).toMatchObject({ state: 'unknown', live: { activeJobs: 1, lastSuccessfulAt: null } })
  })
})
