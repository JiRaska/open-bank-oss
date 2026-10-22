// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Route contract for /api/finops/allocation (ADR-0062): joins the baked cost snapshot with the
// derived footprints and 200s with an allocation; degrades to available:false (still 200) when a
// snapshot is missing, so the page never sees a raw error (admin-ui graceful-state rule).

import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { promises as fs } from 'fs'

const { readSnapshot } = vi.hoisted(() => ({ readSnapshot: vi.fn() }))
vi.mock('@/lib/evidence/contentSnapshot', () => ({ createAdminUiContentSnapshotReader: () => ({ read: readSnapshot }) }))

// Capture the real readFile BEFORE any spy is installed.
// Vitest 4 uses fs.promises.readFile internally (async) to load TypeScript source files
// when a dynamic import() is called without a warm Vite transform cache (always the case
// in CI on a fresh checkout). The mock must passthrough unknown paths to the real readFile
// so that Vitest's module loader can resolve the route + its dependency tree.
const _realReadFile = fs.readFile.bind(fs)
const governancePathBeforeSuite = process.env.OPENBANK_GOVERNANCE

const COST = JSON.stringify({
  available: true, currency: 'USD', periodStart: '2026-05-04', periodEnd: '2026-06-03',
  services: [
    { name: 'Amazon Elastic Compute Cloud - Compute', amount: 90 },
    { name: 'Amazon Elastic Kubernetes Service', amount: 10 },
  ],
  collectedAt: '2026-06-03T00:00:00Z',
})
const FOOTPRINTS = JSON.stringify({
  available: true,
  footprints: [
    { service: 'account-service', cpuMillis: 250, memMiB: 512 },
    { service: 'ledger-service', cpuMillis: 250, memMiB: 512 },
  ],
})

// Resolve fs.readFile by which file the route asks for (cost report vs footprints).
// Unknown paths are passed through to the real readFile so that Vitest's module loader
// (which uses async readFile on a cold transform cache — always in CI) can still resolve
// the route's source files without triggering a spurious ENOENT.
function mockFiles(map: Record<string, string>) {
  vi.spyOn(fs, 'readFile').mockImplementation(async (p: never, ...args: never[]) => {
    const key = String(p)
    if (key.includes('cost-footprints'))
      return (map.footprints ?? Promise.reject(Object.assign(new Error(`ENOENT: open '${key}'`), { code: 'ENOENT' }))) as never
    if (key.includes('cost-report'))
      return (map.cost ?? Promise.reject(Object.assign(new Error(`ENOENT: open '${key}'`), { code: 'ENOENT' }))) as never
    // Passthrough: Vitest module loader, source maps, or any other internal read.
    return _realReadFile(p, ...args) as never
  })
}

describe('GET /api/finops/allocation', () => {
  beforeEach(() => {
    readSnapshot.mockReset().mockResolvedValue({ status: 'unavailable', snapshot: null, provenance: null })
    vi.stubEnv('OPENBANK_COST_REPORT_LIVE', undefined)
    vi.stubEnv('OPENBANK_COST_REPORT', undefined)
    vi.stubEnv('OPENBANK_GOVERNANCE', undefined)
    vi.stubEnv('OPENBANK_COST_FOOTPRINTS', undefined)
  })
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllEnvs() })
  afterAll(() => {
    // VM workers reuse process state across suites; a missing-source fixture must not leak.
    expect(process.env.OPENBANK_GOVERNANCE).toBe(governancePathBeforeSuite)
  })

  it('200s with an allocation joining costs + footprints', async () => {
    mockFiles({ cost: COST, footprints: FOOTPRINTS })
    const { GET } = await import('../app/api/finops/allocation/route')
    const res = await GET()
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.available).toBe(true)
    expect(body.allocatable).toBe(90)
    expect(body.platformOverhead).toBe(10)
    expect(body.byService).toHaveLength(2)
    expect(body.byDomain.find((d: { domain: string }) => d.domain === 'core')).toBeTruthy()
  })

  it('degrades to available:false (still 200) when no snapshot is bundled', async () => {
    mockFiles({})
    const { GET } = await import('../app/api/finops/allocation/route')
    const res = await GET()
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.available).toBe(false)
    expect(body.byService).toEqual([])
  })

  it('still works when footprints are missing (costs but no allocation rows)', async () => {
    mockFiles({ cost: COST })
    const { GET } = await import('../app/api/finops/allocation/route')
    const res = await GET()
    const body = await res.json()
    expect(body.available).toBe(true)
    expect(body.allocatable).toBe(90)
    expect(body.byService).toEqual([])
  })
  it('uses the next published domain revision without an image rebuild and preserves unknown mappings', async () => {
    mockFiles({ cost: COST, footprints: FOOTPRINTS })
    const { GET } = await import('../app/api/finops/allocation/route')
    const provenance = { sourceSha: 'a'.repeat(40), collectedAt: '2026-10-22T10:00:00.000Z', runId: 123, runAttempt: 1 }
    const published = (domain: string, status = 'ready') => ({ status, provenance,
      snapshot: { content: { governance: { services: [{ serviceName: 'openbank-account-service', dataDomain: domain }] } } },
    })
    readSnapshot.mockResolvedValue(published('payments'))
    const first = await (await GET()).json()
    expect(first.byService.find((row: { service: string }) => row.service === 'account-service').domain).toBe('payments')
    expect(first.unmapped).toContain('ledger-service')
    expect(first.governanceSource).toMatchObject({ source: 'published', status: 'ready', sourceSha: provenance.sourceSha, sourceCollectedAt: provenance.collectedAt })
    readSnapshot.mockResolvedValue(published('identity', 'stale'))
    const second = await (await GET()).json()
    expect(second.byService.find((row: { service: string }) => row.service === 'account-service').domain).toBe('identity')
    expect(second.governanceSource).toMatchObject({ source: 'published', status: 'stale', sourceCollectedAt: provenance.collectedAt })
    expect(second.collectedAt).toBe('2026-06-03T00:00:00Z')
  })

  it('labels the image governance fallback stale and rejects unknown domain values', async () => {
    mockFiles({ cost: COST, footprints: FOOTPRINTS })
    const { GET } = await import('../app/api/finops/allocation/route')
    const fallback = await (await GET()).json()
    expect(fallback.governanceSource).toMatchObject({ source: 'image-baked', status: 'stale', sourceCollectedAt: null })
    readSnapshot.mockResolvedValue({ status: 'ready', provenance: { sourceSha: 'b'.repeat(40) },
      snapshot: { content: { governance: { services: [{ serviceName: 'account-service', dataDomain: 'invented' }] } } },
    })
    const unknown = await (await GET()).json()
    expect(unknown.unmapped).toContain('account-service')
    expect(unknown.byService.find((row: { service: string }) => row.service === 'account-service').domain).toBe('platform')
  })

  it('keeps domains unmapped when neither published nor image governance is available', async () => {
    mockFiles({ cost: COST, footprints: FOOTPRINTS })
    vi.stubEnv('OPENBANK_GOVERNANCE', '/nonexistent-test-governance.json')
    const { GET } = await import('../app/api/finops/allocation/route')
    const body = await (await GET()).json()
    expect(body.unmapped).toEqual(['account-service', 'ledger-service'])
    expect(body.byDomain).toEqual([expect.objectContaining({ domain: 'platform' })])
    expect(body.governanceSource).toMatchObject({ source: 'unavailable', status: 'unavailable' })
  })

})
