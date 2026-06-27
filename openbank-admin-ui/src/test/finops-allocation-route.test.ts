// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Route contract for /api/finops/allocation (ADR-0062): joins the baked cost snapshot with the
// derived footprints and 200s with an allocation; degrades to available:false (still 200) when a
// snapshot is missing, so the page never sees a raw error (admin-ui graceful-state rule).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { promises as fs } from 'fs'

// Capture the real readFile BEFORE any spy is installed.
// Vitest 4 uses fs.promises.readFile internally (async) to load TypeScript source files
// when a dynamic import() is called without a warm Vite transform cache (always the case
// in CI on a fresh checkout). The mock must passthrough unknown paths to the real readFile
// so that Vitest's module loader can resolve the route + its dependency tree.
const _realReadFile = fs.readFile.bind(fs)

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
    delete process.env.OPENBANK_COST_REPORT_LIVE
    delete process.env.OPENBANK_COST_REPORT
    delete process.env.OPENBANK_COST_FOOTPRINTS
  })
  afterEach(() => vi.restoreAllMocks())

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
})
