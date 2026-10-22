// SPDX-License-Identifier: Apache-2.0
import { createHash } from 'node:crypto'
import { describe, expect, it, vi } from 'vitest'
import { createEvidenceSnapshotReader, type EvidenceManifest } from '@/lib/evidence/publishedSnapshot'

const COLLECTED = '2026-10-22T10:00:00.000Z'
const START = Date.parse(COLLECTED)
const BASE = 'https://evidence.example/admin-ui/content'
const SHA = 'a'.repeat(40)
interface Snapshot { collectedAt: string; sourceSha: string; values: { label: string }[] }
const snapshot = (label = 'one'): Snapshot => ({ collectedAt: COLLECTED, sourceSha: SHA, values: [{ label }] })
const json = (value: unknown) => new Response(JSON.stringify(value))
function pointer(data = snapshot(), overrides: Partial<EvidenceManifest> = {}): EvidenceManifest {
  const fields = { schemaVersion: 1 as const, sha256: createHash('sha256').update(JSON.stringify(data)).digest('hex'), sourceSha: SHA,
    runId: 1, runAttempt: 1, collectedAt: COLLECTED, expiresAt: new Date(START + 60_000).toISOString(), complete: true as const, ...overrides }
  return { ...fields, snapshot: `snapshots/${fields.runId}-${fields.runAttempt}-${fields.sha256}.json` }
}
function setup(options: { maxDataAgeMs?: number; validForMs?: number } = {}) {
  let time = START
  let manifest: unknown = pointer()
  let data: unknown = snapshot()
  let fail = false
  const validate = vi.fn((value: unknown, now: number): value is Snapshot => Boolean(value && typeof value === 'object'
    && 'values' in value && 'collectedAt' in value && now <= START + (options.validForMs ?? Number.MAX_SAFE_INTEGER)))
  const fetchImpl = vi.fn(async (url: RequestInfo | URL, _init?: RequestInit) => fail ? new Response(null, { status: 503 })
    : json(String(url).endsWith('/current.json') ? manifest : data))
  const reader = createEvidenceSnapshotReader({ baseUrl: BASE, fetchImpl, ttlMs: 10, now: () => time,
    validateSnapshot: validate, collectedAt: value => value.collectedAt, maxDataAgeMs: options.maxDataAgeMs })
  return { reader, fetchImpl, validate, tick: (ms = 11) => { time += ms }, set: (next: unknown, body = data) => { manifest = next; data = body }, fail: (value: boolean) => { fail = value } }
}
const bodyCalls = (fetchImpl: ReturnType<typeof setup>['fetchImpl']) => fetchImpl.mock.calls.filter(([url]) => !String(url).endsWith('/current.json'))

describe('bounded immutable snapshot reuse', () => {
  it('polls and validates every pointer while downloading an unchanged body once', async () => {
    const s = setup()
    for (let n = 0; n < 5; n++) { expect((await s.reader.read()).status).toBe('ready'); s.tick() }
    expect(s.fetchImpl).toHaveBeenCalledTimes(6)
    expect(bodyCalls(s.fetchImpl)).toHaveLength(1)
    expect(s.validate).toHaveBeenCalledTimes(5)
    for (const [, init] of s.fetchImpl.mock.calls) expect(init).toMatchObject({ credentials: 'omit', redirect: 'error', cache: 'no-store' })
  })
  it('refetches changed identities and keeps no historical body cache', async () => {
    const s = setup(); await s.reader.read()
    s.tick(); s.set(pointer(snapshot('two'), { runId: 2 }), snapshot('two'))
    expect((await s.reader.read()).snapshot?.values[0].label).toBe('two')
    s.tick(); s.set(pointer(), snapshot())
    expect((await s.reader.read()).snapshot?.values[0].label).toBe('one')
    expect(bodyCalls(s.fetchImpl)).toHaveLength(3)
  })
  it.each(['sourceSha', 'runId', 'runAttempt', 'expiresAt'] as const)('refetches changed %s', async key => {
    const s = setup(); await s.reader.read(); s.tick()
    const changes = { sourceSha: 'b'.repeat(40), runId: 2, runAttempt: 2, expiresAt: new Date(START + 90_000).toISOString() }
    s.set(pointer(snapshot(), { [key]: changes[key] }))
    const result = await s.reader.read()
    expect(bodyCalls(s.fetchImpl)).toHaveLength(2)
    expect(result.status).toBe(key === 'sourceSha' ? 'stale' : 'ready')
    if (key === 'sourceSha') expect(result.error).toMatch(/source does not match/)
  })
  it('rejects invalid pointers and retains original visibly stale provenance', async () => {
    const s = setup(); await s.reader.read(); s.tick(); s.set({ ...pointer(), complete: false })
    expect(await s.reader.read()).toMatchObject({ status: 'stale', provenance: { sourceSha: SHA } })
    expect(bodyCalls(s.fetchImpl)).toHaveLength(1)
    expect((await s.reader.read()).status).toBe('stale')
    s.tick(); s.set(pointer())
    expect((await s.reader.read()).status).toBe('ready')
    expect(bodyCalls(s.fetchImpl)).toHaveLength(1)
  })
  it('rejects changed body digest and never substitutes its provenance', async () => {
    const s = setup(); await s.reader.read(); s.tick()
    s.set(pointer(snapshot('two'), { runId: 2 }), snapshot('tampered'))
    expect(await s.reader.read()).toMatchObject({ status: 'stale', error: 'snapshot digest mismatch', provenance: { runId: 1 }, snapshot: snapshot() })
  })
  it('keeps failed refresh stale until successful pointer revalidation', async () => {
    const s = setup(); await s.reader.read(); s.tick(); s.fail(true)
    expect((await s.reader.read()).status).toBe('stale')
    s.fail(false)
    expect((await s.reader.read()).status).toBe('stale')
    s.tick()
    expect((await s.reader.read()).status).toBe('ready')
    expect(bodyCalls(s.fetchImpl)).toHaveLength(1)
  })
  it('rechecks expiry, maximum age and time-dependent schema validity', async () => {
    const expired = setup(); await expired.reader.read(); expired.tick(60_001)
    expect(await expired.reader.read()).toMatchObject({ status: 'stale', provenance: { pointerExpired: true } })
    expect(bodyCalls(expired.fetchImpl)).toHaveLength(1)
    const aged = setup({ maxDataAgeMs: 20 }); await aged.reader.read(); aged.tick(21)
    expect(await aged.reader.read()).toMatchObject({ status: 'stale', provenance: { snapshotAgeMs: 21 } })
    const invalid = setup({ validForMs: 20 }); await invalid.reader.read(); invalid.tick(21)
    expect(await invalid.reader.read()).toMatchObject({ status: 'stale', error: 'invalid snapshot schema' })
    expect(bodyCalls(invalid.fetchImpl)).toHaveLength(1)
  })
  it('protects nested verified data and internal provenance from caller mutation', async () => {
    const s = setup(); const first = await s.reader.read()
    expect(() => first.snapshot?.values.push({ label: 'injected' })).toThrow()
    expect(() => { if (first.snapshot) first.snapshot.values[0].label = 'injected' }).toThrow()
    if (first.provenance) first.provenance.sourceSha = 'f'.repeat(40)
    first.snapshot = snapshot('replaced')
    expect(await s.reader.read()).toMatchObject({ snapshot: snapshot(), provenance: { sourceSha: SHA } })
    s.tick()
    expect(await s.reader.read()).toMatchObject({ snapshot: snapshot(), provenance: { sourceSha: SHA } })
    expect(bodyCalls(s.fetchImpl)).toHaveLength(1)
  })
  it('never reuses across stores or after reset', async () => {
    let base = BASE
    const fetchImpl = vi.fn(async (url: RequestInfo | URL, _init?: RequestInit) => json(String(url).endsWith('/current.json') ? pointer() : snapshot()))
    const reader = createEvidenceSnapshotReader({ baseUrl: () => base, fetchImpl, now: () => START,
      validateSnapshot: (value): value is Snapshot => Boolean(value), collectedAt: value => value.collectedAt })
    await reader.read(); base = 'https://other.example/admin-ui/content'; await reader.read()
    base = BASE; await reader.read(); reader.reset(); await reader.read()
    expect(bodyCalls(fetchImpl)).toHaveLength(4)
  })
  it('a late request from a replaced store cannot overwrite the current store cache', async () => {
    let base = BASE
    let release: (response: Response) => void = () => {}
    const blocked = new Promise<Response>(resolve => { release = resolve })
    const fetchImpl = vi.fn(async (url: RequestInfo | URL, _init?: RequestInit) => {
      if (String(url) === `${BASE}/current.json`) return blocked
      const data = snapshot(String(url).startsWith(BASE) ? 'old' : 'new')
      return json(String(url).endsWith('/current.json') ? pointer(data) : data)
    })
    const reader = createEvidenceSnapshotReader({ baseUrl: () => base, fetchImpl, now: () => START,
      validateSnapshot: (value): value is Snapshot => Boolean(value), collectedAt: value => value.collectedAt })
    const oldRead = reader.read()
    base = 'https://other.example/admin-ui/content'
    expect((await reader.read()).snapshot?.values[0].label).toBe('new')
    release(json(pointer(snapshot('old'))))
    expect((await oldRead).snapshot?.values[0].label).toBe('old')
    expect((await reader.read()).snapshot?.values[0].label).toBe('new')
    expect(bodyCalls(fetchImpl)).toHaveLength(2)
  })

})
