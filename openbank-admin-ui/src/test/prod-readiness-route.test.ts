// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { promises as fs } from 'fs'

// The Production Readiness page is a READ-ONLY consumer of the CI-baked
// prod-readiness.json (collector output, ADR-0029 derived-data discipline). These
// tests lock the route contract: pass the bundled scorecard through verbatim, and
// always degrade to a calm empty state — never throw — when the bundle is absent
// or malformed, so the page renders its "no data" panel instead of a 500.

const REPORT = JSON.stringify({
  generated_for: '2026-06-15',
  dimensions: [{ code: 'C1', name: 'Kód' }, { code: 'C5', name: 'Zálohy' }],
  services: [
    { service: 'ledger', money_path: true, scores: { C1: 2, C5: 1 }, evidence: { C1: '39 kt', C5: 'NO backup' }, gate: 'NO-GO' },
  ],
})

describe('GET /api/prod-readiness', () => {
  afterEach(() => vi.restoreAllMocks())

  it('passes the baked scorecard through verbatim', async () => {
    vi.spyOn(fs, 'readFile').mockResolvedValue(REPORT as never)
    const { GET } = await import('../app/api/prod-readiness/route')
    const res = await GET()
    expect(res.status).toBe(200)
    const doc = await res.json()
    expect(doc.services).toHaveLength(1)
    expect(doc.services[0].service).toBe('ledger')
    expect(doc.services[0].gate).toBe('NO-GO')
    expect(doc.dimensions).toHaveLength(2)
  })

  it('degrades to an empty scorecard when the bundle is absent', async () => {
    vi.spyOn(fs, 'readFile').mockRejectedValue(new Error('ENOENT') as never)
    const { GET } = await import('../app/api/prod-readiness/route')
    const res = await GET()
    expect(res.status).toBe(200)
    const doc = await res.json()
    expect(doc.services).toEqual([])
    expect(doc.dimensions).toEqual([])
  })

  it('degrades to an empty scorecard when the bundle is malformed', async () => {
    vi.spyOn(fs, 'readFile').mockResolvedValue('{ not json' as never)
    const { GET } = await import('../app/api/prod-readiness/route')
    const res = await GET()
    expect(res.status).toBe(200)
    const doc = await res.json()
    expect(doc.services).toEqual([])
  })
})
