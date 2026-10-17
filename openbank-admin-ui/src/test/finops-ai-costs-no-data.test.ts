// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'fs'
import path from 'path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// "Langfuse is down" must read as NO DATA. It used to answer totalCostLast7dUsd: 0, which a panel
// renders as "$0.00" -- a number, for a thing nobody measured.
function promResult(value: string) {
  return { ok: true, json: async () => ({ data: { result: [{ value: [0, value] }] } }) }
}
const emptyResult = { ok: true, json: async () => ({ data: { result: [] } }) }

describe('GET /api/finops/ai-costs without data', () => {
  beforeEach(() => vi.resetModules())
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('reports no total (null, not 0) when Prometheus is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('connection refused') }))
    const { GET } = await import('@/app/api/finops/ai-costs/route')

    const body = await (await GET()).json()

    expect(body.available).toBe(false)
    expect(body.totalCostLast7dUsd).toBeNull()
    expect(body.agents).toEqual([])
  })

  it('reports no total when the bridge is up but exports no agent series', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) =>
      decodeURIComponent(String(url)).includes('langfuse_up') ? promResult('1') : emptyResult))
    const { GET } = await import('@/app/api/finops/ai-costs/route')

    const body = await (await GET()).json()

    expect(body.available).toBe(true)
    expect(body.agents).toEqual([])
    expect(body.totalCostLast7dUsd).toBeNull()
  })

  it('still reports a real total when agent series exist (the null is not a blanket)', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) =>
      decodeURIComponent(String(url)).includes('langfuse_up') ? promResult('1') : promResult('2.5')))
    const { GET } = await import('@/app/api/finops/ai-costs/route')

    const body = await (await GET()).json()

    expect(body.available).toBe(true)
    expect(body.agents.length).toBeGreaterThan(0)
    expect(body.totalCostLast7dUsd).toBe(2.5 * body.agents.length)
  })

  it('the FinOps page renders a null total as "No data", never as a formatted dollar amount', () => {
    const page = readFileSync(path.resolve(__dirname, '../app/finops/page.tsx'), 'utf8')
    expect(page).toContain('totalCostLast7dUsd: number | null')
    expect(page).toContain("aiCosts.totalCostLast7dUsd == null")
    expect(page).toContain("'No data'")
  })
})
