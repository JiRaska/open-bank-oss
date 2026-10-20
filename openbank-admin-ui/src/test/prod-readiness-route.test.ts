// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { createHash } from 'node:crypto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { promises as fs } from 'node:fs'
import { readinessRosterDigest, READINESS_DIMENSION_CODES, type ReadinessSnapshotReport } from '../lib/evidence/readinessSnapshot'

const SOURCE_SHA = 'a'.repeat(40)
const CODES = READINESS_DIMENSION_CODES

function report(collectedAt = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')): ReadinessSnapshotReport {
  const services = [{
    service: 'ledger', money_path: false,
    scores: Object.fromEntries(CODES.map(code => [code, 2])) as ReadinessSnapshotReport['services'][number]['scores'],
    evidence: Object.fromEntries(CODES.map(code => [code, 'repository evidence'])) as ReadinessSnapshotReport['services'][number]['evidence'],
    gate: 'GO' as const,
  }]
  return {
    schema_version: 1,
    generated_for: collectedAt.slice(0, 10),
    collected_at: collectedAt,
    source_sha: SOURCE_SHA,
    scope: { kind: 'service-module-matrix-v1', service_count: 1, money_path_count: 0, roster_sha256: readinessRosterDigest(services) },
    dimensions: CODES.map(code => ({ code, name: code })),
    services,
  }
}

function publishedStore(snapshot: ReadinessSnapshotReport, expiresAt = new Date(Date.now() + 60 * 60_000).toISOString()) {
  const body = JSON.stringify(snapshot)
  const sha256 = createHash('sha256').update(body).digest('hex')
  const manifest = JSON.stringify({
    schemaVersion: 1,
    snapshot: `snapshots/17-1-${sha256}.json`,
    sha256,
    sourceSha: SOURCE_SHA,
    runId: 17,
    runAttempt: 1,
    collectedAt: snapshot.collected_at,
    expiresAt,
    complete: true,
  })
  return { body, manifest }
}

function response(body: string, status = 200) {
  return new Response(body, { status, headers: { 'content-type': 'application/json' } })
}

describe('GET /api/prod-readiness', () => {
  afterEach(() => {
    delete process.env.OPENBANK_EVIDENCE_STORE_URL
    delete process.env.OPENBANK_READINESS_EVIDENCE_URL
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    vi.resetModules()
  })

  it('prefers the validated runtime snapshot and preserves its source provenance', async () => {
    process.env.OPENBANK_EVIDENCE_STORE_URL = 'https://evidence.example'
    const live = report()
    const store = publishedStore(live)
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(response(store.manifest))
      .mockResolvedValueOnce(response(store.body)))
    const readFile = vi.spyOn(fs, 'readFile')
    const { GET } = await import('../app/api/prod-readiness/route')

    const res = await GET()
    const doc = await res.json()
    expect(res.status).toBe(200)
    expect(doc.status).toBe('ready')
    expect(doc.source).toBe('runtime')
    expect(doc.report).toEqual(live)
    expect(doc.provenance.sourceSha).toBe(SOURCE_SHA)
    expect(readFile).not.toHaveBeenCalled()
  })

  it('uses a dated, explicitly image-baked snapshot when the runtime snapshot is malformed', async () => {
    process.env.OPENBANK_EVIDENCE_STORE_URL = 'https://evidence.example'
    const baked = report()
    const live = publishedStore(report())
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(response(live.manifest))
      .mockResolvedValueOnce(response(`${live.body} `))) // digest mismatch
    vi.spyOn(fs, 'readFile').mockResolvedValue(JSON.stringify(baked) as never)
    const { GET } = await import('../app/api/prod-readiness/route')

    const res = await GET()
    const doc = await res.json()
    expect(doc.status).toBe('image-baked')
    expect(doc.source).toBe('image-baked')
    expect(doc.report).toEqual(baked)
    expect(doc.provenance).toBeNull()
  })

  it('returns a stale runtime snapshot as stale instead of masking it with the baked report', async () => {
    process.env.OPENBANK_EVIDENCE_STORE_URL = 'https://evidence.example'
    const collectedAt = new Date(Date.now() - 3 * 60 * 60_000).toISOString().replace(/\.\d{3}Z$/, 'Z')
    const live = report(collectedAt)
    const store = publishedStore(live, new Date(Date.now() - 60_000).toISOString())
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(response(store.manifest))
      .mockResolvedValueOnce(response(store.body)))
    vi.spyOn(fs, 'readFile').mockResolvedValue(JSON.stringify(report()) as never)
    const { GET } = await import('../app/api/prod-readiness/route')

    const res = await GET()
    const doc = await res.json()
    expect(doc.status).toBe('stale')
    expect(doc.source).toBe('runtime')
    expect(doc.report).toEqual(live)
  })

  it('reports unavailable instead of manufacturing an empty scorecard when no valid source exists', async () => {
    vi.spyOn(fs, 'readFile').mockRejectedValue(new Error('ENOENT') as never)
    const { GET } = await import('../app/api/prod-readiness/route')
    const res = await GET()

    expect(res.status).toBe(503)
    await expect(res.json()).resolves.toEqual({ status: 'unavailable', source: null, report: null, provenance: null })
  })

  it('rejects a baked report whose roster metadata does not match its rows', async () => {
    const invalid = report()
    invalid.scope.service_count = 55
    vi.spyOn(fs, 'readFile').mockResolvedValue(JSON.stringify(invalid) as never)
    const { GET } = await import('../app/api/prod-readiness/route')
    const res = await GET()

    expect(res.status).toBe(503)
    await expect(res.json()).resolves.toMatchObject({ status: 'unavailable', report: null })
  })
})
