// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mkdtempSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
import { createSnapshotCache } from '@/lib/evidence/snapshotCache'
import { createCommitEvidenceReader, storeBaseUrl } from '@/lib/evidence/commitEvidence'
import { commitFromImage } from '@/lib/evidence/runningCommits'
import { overlayRunningEvidence, snapshotCommitOf } from '@/lib/evidence/overlay'
import type { ComponentTestPosture, TestIntelligenceReport } from '@/lib/types/test-intelligence'

const running = vi.hoisted(() => ({ calls: 0, map: null as Map<string, { component: string; commit: string | null; image: string | null }> | null }))
vi.mock('@/lib/evidence/runningCommits', async importOriginal => ({
  ...(await importOriginal<typeof import('@/lib/evidence/runningCommits')>()),
  loadRunningCommits: async () => { running.calls += 1; return running.map },
}))

const posture = (component: string, commit: string | null): ComponentTestPosture => ({
  component, released: true, moneyPath: false,
  evidence: commit ? [{ kind: 'unit', state: 'passed', observedAt: '2026-10-17T10:00:00Z', source: 'x', environment: 'ci',
    run: { id: '1', attempt: 1, commit, branch: 'main', workflow: 'ci', url: 'https://github.com/o/r/actions/runs/1' } }] : [],
  coverage: { state: 'not-run', observedAt: null, lines: { covered: 0, missed: 0, percentage: null }, branches: { covered: 0, missed: 0, percentage: null }, source: null },
  testInfrastructure: { declared: [], observed: [] },
})

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const deferred = <T>() => { let resolve!: (v: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }

describe('snapshot cache', () => {
  it('collapses concurrent viewers into ONE load', async () => {
    const gate = deferred<number>()
    const load = vi.fn(() => gate.promise)
    const cache = createSnapshotCache({ load, ttlMs: 30_000 })
    const viewers = Array.from({ length: 500 }, () => cache.get())
    gate.resolve(42)
    expect((await Promise.all(viewers)).every(s => s.value === 42)).toBe(true)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('serves the last value instantly while ONE background refresh runs', async () => {
    let t = 0
    const second = deferred<string>()
    const load = vi.fn().mockResolvedValueOnce('old').mockImplementationOnce(() => second.promise)
    const cache = createSnapshotCache({ load, ttlMs: 1000, now: () => t })
    expect((await cache.get()).value).toBe('old')
    t = 5000
    expect((await cache.get()).value).toBe('old')
    expect((await cache.get()).value).toBe('old')
    expect(load).toHaveBeenCalledTimes(2)
    second.resolve('new')
    await second.promise
    await Promise.resolve()
    expect((await cache.get()).value).toBe('new')
  })

  it('keeps the last good value on failure and backs off instead of retrying per request', async () => {
    let t = 0
    const load = vi.fn().mockResolvedValueOnce('good').mockRejectedValue(new Error('down'))
    const cache = createSnapshotCache({ load, ttlMs: 1000, retryMs: 10_000, now: () => t })
    await cache.get()
    t = 2000
    await cache.get()
    await new Promise(r => setTimeout(r, 0))
    for (let i = 0; i < 50; i += 1) expect((await cache.get()).value).toBe('good')
    expect(load).toHaveBeenCalledTimes(2)
  })
})

describe('commit-addressed evidence store', () => {
  const BASE = 'https://evidence.internal/ti'

  it('is disabled without a valid https base and never fetches', async () => {
    const fetchImpl = vi.fn()
    expect(await createCommitEvidenceReader({ baseUrl: '', fetchImpl }).read('openbank-ledger-service', 'abc1234')).toEqual({ status: 'disabled' })
    expect(storeBaseUrl('http://x/y')).toBeNull()
    expect(storeBaseUrl('https://u:p@x/y')).toBeNull()
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('fetches a commit ONCE and serves it forever — evidence for a commit is immutable', async () => {
    let t = 0
    const fetchImpl = vi.fn().mockImplementation(async () => json(posture('openbank-ledger-service', 'abc1234def')))
    const reader = createCommitEvidenceReader({ baseUrl: BASE, fetchImpl, now: () => t })
    await Promise.all(Array.from({ length: 100 }, () => reader.read('openbank-ledger-service', 'abc1234def')))
    t = 365 * 86_400_000
    expect((await reader.read('openbank-ledger-service', 'abc1234def')).status).toBe('hit')
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(fetchImpl.mock.calls[0][0]).toBe(`${BASE}/openbank-ledger-service/abc1234def/test-intelligence.json`)
  })

  it('rejects a posture whose runs name another commit', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(posture('openbank-ledger-service', 'fff9999')))
    const result = await createCommitEvidenceReader({ baseUrl: BASE, fetchImpl }).read('openbank-ledger-service', 'abc1234')
    expect(result.status).toBe('error')
  })

  it('accepts runs from another commit ONLY when declared tree-equivalent, and rejects lies', async () => {
    const tested = 'aaaaaaaa'.padEnd(40, '1')
    const stored = (evidenceSource: unknown) => ({ ...posture('openbank-ab', tested), evidenceSource })
    const read = async (body: unknown) => createCommitEvidenceReader({ baseUrl: 'https://store.example', fetchImpl: async () => json(body) }).read('openbank-ab', 'bbbbbbbb')
    expect((await read(stored({ commit: tested, equivalence: 'tree-equivalent' }))).status).toBe('hit')
    // undeclared: runs name another commit than the key
    expect((await read(posture('openbank-ab', tested))).status).toBe('error')
    // declared "identical" while the tested commit is not the keyed one
    expect((await read(stored({ commit: tested, equivalence: 'identical' }))).status).toBe('error')
    // unknown equivalence kind, short commit, runs naming a third commit
    expect((await read(stored({ commit: tested, equivalence: 'probably' }))).status).toBe('error')
    expect((await read(stored({ commit: 'aaaaaaaa', equivalence: 'tree-equivalent' }))).status).toBe('error')
    expect((await read(stored({ commit: 'c'.repeat(40), equivalence: 'tree-equivalent' }))).status).toBe('error')
  })

  it('remembers a miss for its TTL, then looks again', async () => {
    let t = 0
    const fetchImpl = vi.fn().mockImplementation(async () => new Response('', { status: 404 }))
    const reader = createCommitEvidenceReader({ baseUrl: BASE, fetchImpl, now: () => t, missTtlMs: 1000 })
    for (let i = 0; i < 20; i += 1) expect((await reader.read('openbank-ledger-service', 'abc1234')).status).toBe('miss')
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    t = 1001
    await reader.read('openbank-ledger-service', 'abc1234')
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  it('opens the breaker after repeated failures', async () => {
    let t = 0
    const fetchImpl = vi.fn().mockRejectedValue(new Error('timeout'))
    const reader = createCommitEvidenceReader({ baseUrl: BASE, fetchImpl, now: () => t, errorRetryMs: 10, breakerThreshold: 3, breakerOpenMs: 60_000 })
    for (let i = 0; i < 3; i += 1) { await reader.read('openbank-ledger-service', 'abc1234'); t += 11 }
    for (let i = 0; i < 30; i += 1) { await reader.read('openbank-ledger-service', 'abc1234'); t += 11 }
    expect(fetchImpl).toHaveBeenCalledTimes(3)
  })

  it('never has more than `concurrency` requests in flight', async () => {
    let active = 0, peak = 0
    const fetchImpl = vi.fn().mockImplementation(async (url: string) => {
      active += 1; peak = Math.max(peak, active)
      await new Promise(r => setTimeout(r, 5))
      active -= 1
      const component = url.split('/')[4]
      return json(posture(component, 'abc1234'))
    })
    const reader = createCommitEvidenceReader({ baseUrl: BASE, fetchImpl, concurrency: 4 })
    await Promise.all(Array.from({ length: 40 }, (_, i) => reader.read(`openbank-svc-${i}`, 'abc1234')))
    expect(fetchImpl).toHaveBeenCalledTimes(40)
    expect(peak).toBeLessThanOrEqual(4)
  })

  it('bounds memory with an LRU', async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => new Response('', { status: 404 }))
    const reader = createCommitEvidenceReader({ baseUrl: BASE, fetchImpl, maxEntries: 10 })
    for (let i = 0; i < 50; i += 1) await reader.read('openbank-ledger-service', `abc${String(i).padStart(4, '0')}`)
    expect(reader.size()).toBe(10)
  })
})

describe('running commit join', () => {
  it('reads the commit from the auto-deploy image tag and nothing else', () => {
    expect(commitFromImage('123.dkr.ecr.eu-north-1.amazonaws.com/openbank-admin-ui:sandbox-18fd3216e-run38721893445')).toBe('18fd3216e')
    expect(commitFromImage('repo/x:sandbox-18fd3216e')).toBe('18fd3216e')
    expect(commitFromImage('repo/x:latest')).toBeNull()
    expect(commitFromImage(undefined)).toBeNull()
  })

  it('prefers store evidence for the running commit and labels every other case', async () => {
    const report = { components: [
      posture('openbank-a', '1111111aaaa'), posture('openbank-b', '2222222bbbb'), posture('openbank-c', '3333333cccc'),
    ] } as unknown as TestIntelligenceReport
    const map = new Map([
      ['openbank-a', { component: 'openbank-a', commit: '9999999', image: null }],
      ['openbank-b', { component: 'openbank-b', commit: '2222222', image: null }],
    ])
    const read = vi.fn(async (component: string) => component === 'openbank-a'
      ? { status: 'hit' as const, posture: posture('openbank-a', '9999999') }
      : { status: 'miss' as const })
    const out = await overlayRunningEvidence(report, map, read)
    const [a, b, c] = out.components.map(x => x.running)
    expect(a).toMatchObject({ source: 'running-commit', runningCommit: '9999999', match: true, storeStatus: 'hit' })
    expect(snapshotCommitOf(out.components[0])).toBe('9999999')
    expect(b).toMatchObject({ source: 'snapshot', snapshotCommit: '2222222bbbb', match: true, storeStatus: 'miss' })
    expect(c).toMatchObject({ source: 'snapshot', runningCommit: null, match: null, storeStatus: 'not-running' })
    expect(read).toHaveBeenCalledTimes(2)
  })

  it('labels a hit tested on a build-identical commit', async () => {
    const tested = 'a'.repeat(40)
    const report = { components: [posture('openbank-a', '1111111aaaa')] } as unknown as TestIntelligenceReport
    const map = new Map([['openbank-a', { component: 'openbank-a', commit: '9999999', image: null }]])
    const out = await overlayRunningEvidence(report, map, async () => ({ status: 'hit' as const,
      posture: { ...posture('openbank-a', tested), evidenceSource: { commit: tested, equivalence: 'tree-equivalent' as const } } }))
    expect(out.components[0].running).toMatchObject({ storeStatus: 'hit', runningCommit: '9999999', evidenceCommit: tested, equivalence: 'tree-equivalent' })
  })

  it('keeps cross-source rows (Pact, Pitest, k6, simulation) on a store hit and drops the stale envelope rows', async () => {
    const snapshot = posture('openbank-a', '1111111aaaa')
    snapshot.evidence.push(
      { kind: 'contract', state: 'passed', observedAt: '2026-10-17T09:00:00Z', source: 'Pact:openbank-x-openbank-a.json', environment: 'ci' },
      { kind: 'mutation', state: 'failed', observedAt: '2026-10-17T09:00:00Z', source: 'Pitest:mutations.xml', environment: 'ci' },
      { kind: 'performance', state: 'passed', observedAt: '2026-10-17T09:00:00Z', source: 'k6:smoke', environment: 'sandbox' },
      { kind: 'simulation', state: 'passed', observedAt: '2026-10-17T09:00:00Z', source: 'JUnit:test/Scenario.xml', environment: 'ci' },
    )
    const report = { components: [snapshot] } as unknown as TestIntelligenceReport
    const map = new Map([['openbank-a', { component: 'openbank-a', commit: '9999999', image: null }]])
    const out = await overlayRunningEvidence(report, map, async () => ({ status: 'hit' as const, posture: posture('openbank-a', '9999999') }))
    const evidence = out.components[0].evidence
    expect(evidence.map(item => item.kind).sort()).toEqual(['contract', 'mutation', 'performance', 'simulation', 'unit'])
    // the snapshot's own run-envelope row (another commit) must NOT survive next to the store's
    expect(evidence.filter(item => item.run).map(item => item.run!.commit)).toEqual(['9999999'])
  })
})

describe('GET /api/test-intelligence under load', () => {
  const dirs: string[] = []
  afterEach(() => {
    delete process.env.OPENBANK_TEST_INTELLIGENCE
    running.calls = 0; running.map = null
    dirs.splice(0).forEach(dir => rmSync(dir, { recursive: true, force: true }))
  })

  const bundle = () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'ti-load-'))
    dirs.push(dir)
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: new Date().toISOString(),
      components: [posture('openbank-ledger-service', 'abc1234def')], contracts: [], mutations: [], performance: [], syntheticJourneys: [],
      history: [], testCases: [], totals: { components: 1, componentsWithExecutionEvidence: 1, moneyPathComponents: 0, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 }, warnings: [],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
  }

  it('answers 200 viewers with ONE assembly and the same ETag', async () => {
    bundle()
    running.map = new Map([['openbank-ledger-service', { component: 'openbank-ledger-service', commit: 'abc1234', image: null }]])
    const { GET } = await import('@/app/api/test-intelligence/route')
    const responses = await Promise.all(Array.from({ length: 200 }, () => GET(new Request('http://x/api/test-intelligence'))))
    expect(running.calls).toBe(1)
    const tags = new Set(responses.map(r => r.headers.get('etag')))
    expect(tags.size).toBe(1)
    const body = await responses[0].json()
    expect(body.components[0].running).toMatchObject({ runningCommit: 'abc1234', match: true, source: 'snapshot', storeStatus: 'disabled' })
    expect(body.evidence).toMatchObject({
      source: 'bundled',
      dataset: { status: 'disabled' },
      runningCommits: { known: 1, matchingSnapshot: 1, fromStore: 0, fromEquivalentBuild: 0, fromBundledFallback: 1, mismatchedEvidence: 0 },
    })
  })

  it('revalidates an unchanged poll as a bodyless 304', async () => {
    bundle()
    const { GET } = await import('@/app/api/test-intelligence/route')
    const first = await GET(new Request('http://x/api/test-intelligence'))
    const etag = first.headers.get('etag') as string
    const second = await GET(new Request('http://x/api/test-intelligence', { headers: { 'If-None-Match': etag } }))
    expect(second.status).toBe(304)
    expect(await second.text()).toBe('')
    expect(first.headers.get('cache-control')).toBe('private, no-cache')
  })

  it('reports running commits as unknown off-cluster instead of guessing', async () => {
    bundle()
    const { GET } = await import('@/app/api/test-intelligence/route')
    const body = await (await GET(new Request('http://admin.test/api/test-intelligence'))).json()
    expect(body.evidence.runningCommits.known).toBeNull()
    expect(body.components[0].running).toMatchObject({ match: null, storeStatus: 'not-running' })
  })
})
