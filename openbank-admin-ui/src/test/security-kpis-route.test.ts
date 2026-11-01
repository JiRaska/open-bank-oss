// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { promises as fs } from 'fs'
import os from 'os'
import path from 'path'
import { createHash } from 'node:crypto'

// /api/security/kpis — ADR-0056 envelope over the CI-generated snapshot.
// The three states that must never blur: baked snapshot (available), absent file
// (not_deployed — an older image, NOT "everything is fine"), corrupt file (error).

let tmpDir: string

beforeEach(async () => {
  vi.resetModules()
  delete process.env.OPENBANK_EVIDENCE_STORE_URL
  delete process.env.OPENBANK_SECURITY_KPIS_SNAPSHOT_URL
  tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'kpis-'))
})

afterEach(() => {
  delete process.env.OPENBANK_SECURITY_KPIS
  delete process.env.OPENBANK_EVIDENCE_STORE_URL
  delete process.env.OPENBANK_SECURITY_KPIS_SNAPSHOT_URL
  vi.unstubAllGlobals()
})

const currentSnapshot = (generatedAt = new Date().toISOString()) => ({
  generatedAt,
  netpol: { available: true, covered: 8, total: 10, coveragePct: 80, gateGreen: true },
  freshness: { available: true, fleetScore: 75, scoredModules: 12, unknownModules: 2 },
  credentials: { available: false, reason: 'source unavailable' },
  fuzz: { available: false, reason: 'source unavailable' },
  threatModels: { available: false, reason: 'source unavailable' },
  mttr: { available: false, reason: 'source unavailable' },
})

async function loadGet() {
  const { GET } = await import('@/app/api/security/kpis/route')
  return GET
}

describe('GET /api/security/kpis', () => {
  it('serves the baked snapshot with per-signal availability intact', async () => {
    const snapshot = {
      generatedAt: '2026-09-04T21:00:00Z',
      netpol: { available: true, covered: 59, total: 73, coveragePct: 81, gateGreen: true },
      freshness: { available: false, reason: 'skipped' },
      credentials: { available: true, totalSecrets: 165, staticSecrets: 158, withDeadline: 0, overdue: 0, undeclared: 158, overdueFound: false },
      fuzz: { available: false, reason: 'no fleet artifact' },
      threatModels: { available: false, reason: 'collector unavailable' },
      mttr: { available: false, reason: 'token unavailable' },
    }
    const file = path.join(tmpDir, 'security-kpis.json')
    await fs.writeFile(file, JSON.stringify(snapshot))
    process.env.OPENBANK_SECURITY_KPIS = file

    const res = await (await loadGet())()
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.available).toBe(true)
    expect(body.kpis.netpol.coveragePct).toBe(81)
    // A degraded collector must pass through as-is, never flatten the envelope.
    expect(body.kpis.freshness.available).toBe(false)
  })

  it('absent snapshot file is not_deployed, HTTP 200', async () => {
    process.env.OPENBANK_SECURITY_KPIS = path.join(tmpDir, 'does-not-exist.json')
    const res = await (await loadGet())()
    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toEqual({ available: false, reason: 'not_deployed' })
  })

  it('corrupt snapshot file is error, not a 500', async () => {
    const file = path.join(tmpDir, 'security-kpis.json')
    await fs.writeFile(file, '{not json')
    process.env.OPENBANK_SECURITY_KPIS = file
    const res = await (await loadGet())()
    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toMatchObject({ available: false, reason: 'error' })
  })

  it('rejects a contradictory snapshot instead of serving authoritative-looking KPI data', async () => {
    const snapshot = {
      generatedAt: '2026-09-04T21:00:00Z',
      netpol: { available: true, covered: 59, total: 73, coveragePct: 100, gateGreen: true },
      freshness: { available: false, reason: 'skipped' },
      credentials: { available: false, reason: 'skipped' },
      fuzz: { available: false, reason: 'skipped' },
      threatModels: { available: false, reason: 'skipped' },
      mttr: { available: false, reason: 'skipped' },
    }
    const file = path.join(tmpDir, 'security-kpis.json')
    await fs.writeFile(file, JSON.stringify(snapshot))
    process.env.OPENBANK_SECURITY_KPIS = file

    const res = await (await loadGet())()
    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toMatchObject({ available: false, reason: 'error' })
  })

  it('serves the exact published weekly snapshot and provenance without rewriting generatedAt', async () => {
    const now = Date.now()
    const collectedAt = new Date(now - 60_000).toISOString()
    const generatedAt = collectedAt
    const snapshot = currentSnapshot(generatedAt)
    const bytes = JSON.stringify(snapshot)
    const sha256 = createHash('sha256').update(bytes).digest('hex')
    const pointer = {
      schemaVersion: 1,
      snapshot: `snapshots/123-1-${sha256}.json`,
      sha256,
      sourceSha: 'a'.repeat(40),
      runId: 123,
      runAttempt: 1,
      collectedAt,
      expiresAt: new Date(now + 2 * 864e5).toISOString(),
      complete: true,
    }
    process.env.OPENBANK_EVIDENCE_STORE_URL = 'https://evidence.example/store'
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => String(input).endsWith('/current.json')
      ? Response.json(pointer)
      : new Response(bytes, { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const body = await (await (await loadGet())()).json()
    expect(body).toMatchObject({
      available: true,
      status: 'ready',
      source: 'runtime',
      kpis: { generatedAt },
      provenance: { sourceSha: 'a'.repeat(40), runId: 123, collectedAt },
    })
    expect(body.kpis.generatedAt).toBe(generatedAt)
  })

  it('does not serve an image-baked green fallback when the configured publisher is unavailable', async () => {
    const file = path.join(tmpDir, 'security-kpis.json')
    await fs.writeFile(file, JSON.stringify(currentSnapshot()))
    process.env.OPENBANK_SECURITY_KPIS = file
    process.env.OPENBANK_EVIDENCE_STORE_URL = 'https://evidence.example/store'
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 503 })))

    const body = await (await (await loadGet())()).json()
    expect(body).toMatchObject({ available: false, reason: 'error' })
  })

  it('reports a stale published KPI snapshot as stale and preserves its original generatedAt', async () => {
    const now = Date.now()
    const collectedAt = new Date(now - 9 * 864e5).toISOString()
    const generatedAt = collectedAt
    const bytes = JSON.stringify(currentSnapshot(generatedAt))
    const sha256 = createHash('sha256').update(bytes).digest('hex')
    const pointer = {
      schemaVersion: 1, snapshot: `snapshots/124-1-${sha256}.json`, sha256,
      sourceSha: 'b'.repeat(40), runId: 124, runAttempt: 1, collectedAt,
      expiresAt: new Date(now + 864e5).toISOString(), complete: true,
    }
    process.env.OPENBANK_EVIDENCE_STORE_URL = 'https://evidence.example/store'
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => String(input).endsWith('/current.json')
      ? Response.json(pointer)
      : new Response(bytes, { status: 200 })))

    const body = await (await (await loadGet())()).json()
    expect(body).toMatchObject({ available: true, status: 'stale', source: 'runtime', kpis: { generatedAt } })
  })
})
