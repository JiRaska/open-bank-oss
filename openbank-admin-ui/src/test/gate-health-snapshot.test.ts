// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { createHash } from 'node:crypto'
import { describe, expect, it, vi } from 'vitest'
import {
  createGateHealthSnapshotReader,
  gateHealthStoreBaseUrl,
  validateGateHealthSnapshotData,
  type GateHealthManifest,
  type GateHealthSnapshotData,
} from '@/lib/evidence/gateHealthSnapshot'
import { validateEvidenceManifest } from '@/lib/evidence/publishedSnapshot'

const BASE = 'https://evidence.example/gate-health'
const COLLECTED = '2026-10-19T10:00:00.000Z'
const SHA = 'a'.repeat(40)
const data = (overrides: Partial<GateHealthSnapshotData> = {}): GateHealthSnapshotData => ({
  available: true,
  source: 'github-actions',
  collectedAt: COLLECTED,
  runsInspected: 12,
  gateDetailRunsInspected: 8,
  shardHistory: [{ runId: 42, sha: SHA, event: 'push', createdAt: COLLECTED, shards: [{ name: 'unit', conclusion: 'success', seconds: 41 }] }],
  gates: [{ id: 'unit-tests', group: 'quality', mode: 'enforced', status: 'passing', lastRed: null, flaky: false, selftestDeclared: true, minSubjects: 10, budgetSeconds: 60, runsObserved: 12 }],
  estate: { total: 4, enforced: 3, advisory: 1, withSelftest: 3, withFloor: 2, withBudget: 2, flaky: 0 },
  ...overrides,
})

function encodedSnapshot(snapshot: GateHealthSnapshotData) {
  const bytes = new TextEncoder().encode(JSON.stringify(snapshot))
  return { bytes, digest: createHash('sha256').update(bytes).digest('hex') }
}

const responseBytes = (bytes: Uint8Array) => new Response(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer)

function manifest(snapshot: GateHealthSnapshotData, options: Partial<GateHealthManifest> = {}): GateHealthManifest {
  const encoded = encodedSnapshot(snapshot)
  const runId = options.runId ?? 42
  const runAttempt = options.runAttempt ?? 1
  return {
    schemaVersion: 1,
    snapshot: `snapshots/${runId}-${runAttempt}-${encoded.digest}.json`,
    sha256: encoded.digest,
    sourceSha: SHA,
    runId,
    runAttempt,
    collectedAt: snapshot.collectedAt,
    expiresAt: '2026-10-20T10:00:00.000Z',
    complete: true,
    ...options,
  }
}

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'content-type': 'application/json' } })
function storeFetch(pointer: unknown, snapshotBytes?: Uint8Array) {
  return vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = String(input)
    if (url === `${BASE}/current.json`) return json(pointer)
    if (url.startsWith(`${BASE}/snapshots/`)) return responseBytes(snapshotBytes ?? new TextEncoder().encode(JSON.stringify(data())))
    return new Response(null, { status: 404 })
  })
}

describe('Gate Health runtime snapshot reader', () => {
  it('disables the store unless the configured base is a safe HTTPS URL', async () => {
    const fetchImpl = vi.fn()
    expect(gateHealthStoreBaseUrl('http://evidence.example')).toBeNull()
    expect(gateHealthStoreBaseUrl('https://user:pass@evidence.example')).toBeNull()
    expect(gateHealthStoreBaseUrl('https://evidence.example/?x=1')).toBeNull()
    expect(await createGateHealthSnapshotReader({ fetchImpl }).read()).toEqual({ status: 'disabled', snapshot: null, provenance: null })
    expect((await createGateHealthSnapshotReader({ baseUrl: 'http://evidence.example', fetchImpl }).read()).status).toBe('unavailable')
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('validates the current gate-health payload contract', () => {
    const now = Date.parse(COLLECTED)
    expect(validateGateHealthSnapshotData(data(), now)).toBe(true)
    expect(validateGateHealthSnapshotData({ ...data(), available: false }, now)).toBe(false)
    expect(validateGateHealthSnapshotData(data({ gates: [{ ...data().gates[0], runsObserved: Number.NaN }] }), now)).toBe(false)
  })

  it('requires explicit timezone offsets on shared evidence manifest timestamps', () => {
    const snapshot = data()
    const good = manifest(snapshot)
    expect(validateEvidenceManifest(good, Date.parse(COLLECTED))).toBe(true)
    expect(validateEvidenceManifest({ ...good, collectedAt: '2026-10-19T10:00:00' }, Date.parse(COLLECTED))).toBe(false)
    expect(validateEvidenceManifest({ ...good, expiresAt: '2026-10-20T10:00:00' }, Date.parse(COLLECTED))).toBe(false)
    expect(validateEvidenceManifest({ ...good, expiresAt: '2026-10-20T10:00:00+02:00' }, Date.parse(COLLECTED))).toBe(true)
  })

  it('fetches a verified snapshot with no credentials and safe redirect behavior', async () => {
    const snapshot = data()
    const { bytes } = encodedSnapshot(snapshot)
    const fetchImpl = storeFetch(manifest(snapshot), bytes)
    const reader = createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl, now: () => Date.parse(COLLECTED) })
    const result = await reader.read()
    expect(result.status).toBe('ready')
    expect(result.snapshot).toEqual(snapshot)
    expect(result.provenance).toMatchObject({ sourceSha: SHA, runId: 42, runAttempt: 1, snapshotAgeMs: 0, pointerExpired: false })
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    for (const [, init] of fetchImpl.mock.calls) expect(init).toMatchObject({ credentials: 'omit', redirect: 'error', cache: 'no-store' })
    expect(String(fetchImpl.mock.calls[1][0])).toBe(`${BASE}/${manifest(snapshot).snapshot}`)
  })

  it('rejects a digest mismatch and never follows a pointer outside the snapshot key grammar', async () => {
    const snapshot = data()
    const fetchImpl = storeFetch(manifest(snapshot), new TextEncoder().encode(JSON.stringify({ ...snapshot, runsInspected: 999 })))
    const reader = createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl, now: () => Date.parse(COLLECTED) })
    expect((await reader.read()).status).toBe('unavailable')
    const unsafe = storeFetch({ ...manifest(snapshot), snapshot: 'https://attacker.example/x' })
    expect((await createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl: unsafe }).read()).status).toBe('unavailable')
    expect(unsafe).toHaveBeenCalledTimes(1)
  })

  it('rejects malformed manifests and snapshots', async () => {
    const snapshot = data()
    const malformedManifest = storeFetch({ ...manifest(snapshot), complete: false })
    expect((await createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl: malformedManifest }).read()).status).toBe('unavailable')
    expect(malformedManifest).toHaveBeenCalledTimes(1)
    const malformedData = { ...snapshot, gates: 'not-an-array' }
    const malformed = storeFetch(manifest(snapshot), new TextEncoder().encode(JSON.stringify(malformedData)))
    expect((await createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl: malformed }).read()).status).toBe('unavailable')
  })

  it('returns unavailable on timeout and retains a previous good snapshot as stale on refresh failure', async () => {
    let now = Date.parse(COLLECTED)
    const snapshot = data()
    let calls = 0
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      calls += 1
      if (calls > 2) throw new Error('network timeout')
      const url = String(input)
      if (url.endsWith('/current.json')) return json(manifest(snapshot))
      return responseBytes(encodedSnapshot(snapshot).bytes)
    })
    const reader = createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl, ttlMs: 1000, now: () => now })
    expect((await reader.read()).status).toBe('ready')
    now += 1001
    const failedRefresh = await reader.read()
    expect(failedRefresh.status).toBe('stale')
    expect(failedRefresh.snapshot).toEqual(snapshot)
    expect(failedRefresh.error).toMatch(/timeout/)
    expect((await reader.read()).status).toBe('stale')
    expect((await createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl: async (_url: RequestInfo | URL, init?: RequestInit) => {
      await new Promise((_, reject) => init?.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true }))
      throw new Error('unexpected')
    }, timeoutMs: 5 }).read()).status).toBe('unavailable')
  })

  it('reports pointer expiry separately from snapshot age and permits a fresh-pointer rollback', async () => {
    let now = Date.parse('2026-10-20T10:00:00.000Z')
    const old = data({ collectedAt: '2026-09-01T10:00:00.000Z' })
    const oldManifest = manifest(old, { expiresAt: '2026-10-20T09:00:00.000Z', runId: 41, runAttempt: 1 })
    const fetchImpl = storeFetch(oldManifest, encodedSnapshot(old).bytes)
    const reader = createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl, now: () => now, maxDataAgeMs: 24 * 60 * 60 * 1000 })
    const expired = await reader.read()
    expect(expired.status).toBe('stale')
    expect(expired.provenance).toMatchObject({ pointerExpired: true, snapshotAgeMs: now - Date.parse(old.collectedAt) })

    now += 1000
    const rolledBack = data({ collectedAt: old.collectedAt })
    const rollbackManifest = manifest(rolledBack, { runId: 40, expiresAt: '2026-10-21T10:00:00.000Z' })
    const rollbackReader = createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl: storeFetch(rollbackManifest, encodedSnapshot(rolledBack).bytes), now: () => now, maxDataAgeMs: 24 * 60 * 60 * 1000 })
    const rollback = await rollbackReader.read()
    expect(rollback.status).toBe('stale')
    expect(rollback.provenance).toMatchObject({ runId: 40, pointerExpired: false })
    expect(rollback.provenance?.snapshotAgeMs).toBeGreaterThan(24 * 60 * 60 * 1000)
  })

  it('rejects collection timestamps more than five minutes ahead of the reader clock', async () => {
    const future = data({ collectedAt: '2026-10-19T10:06:00.000Z' })
    const fetchImpl = storeFetch(manifest(future), encodedSnapshot(future).bytes)
    const result = await createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl, now: () => Date.parse(COLLECTED) }).read()
    expect(result.status).toBe('unavailable')
    expect(result.error).toMatch(/manifest/i)
  })

  it('observes a new pointer after the bounded cache TTL, including a rollback to an older run', async () => {
    let now = Date.parse(COLLECTED)
    let selected = data()
    let runId = 43
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/current.json')) return json(manifest(selected, { runId }))
      return responseBytes(encodedSnapshot(selected).bytes)
    })
    const reader = createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl, ttlMs: 1000, now: () => now })
    expect((await reader.read()).provenance?.runId).toBe(43)
    selected = data({ runsInspected: 4 })
    runId = 12 // a valid rollback target; source run ordering is not a validity rule
    now += 1001
    const result = await reader.read()
    expect(result.provenance?.runId).toBe(12)
    expect(result.snapshot?.runsInspected).toBe(4)
    expect(fetchImpl).toHaveBeenCalledTimes(4)
  })

  it('coalesces concurrent reads into one manifest and snapshot fetch pair', async () => {
    const snapshot = data()
    let release!: (value: Response) => void
    const gate = new Promise<Response>(resolve => { release = resolve })
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/current.json') ? gate : responseBytes(encodedSnapshot(snapshot).bytes))
    const reader = createGateHealthSnapshotReader({ baseUrl: BASE, fetchImpl, now: () => Date.parse(COLLECTED) })
    const pending = Array.from({ length: 50 }, () => reader.read())
    release(json(manifest(snapshot)))
    const results = await Promise.all(pending)
    expect(results.every(result => result.status === 'ready')).toBe(true)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })
})
