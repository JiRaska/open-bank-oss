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

    expect(body.available).toBe(false)
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
  it.each([
    ['Czech', 'Metriky nákladů na AI nejsou k dispozici.', 'bridge není nasazen'],
    ['English', 'AI cost metrics are unavailable.', 'bridge is not deployed'],
  ])('uses truthful unavailable copy in %s without assuming deployment absence', (_locale, expected, falseClaim) => {
    const page = readFileSync(path.resolve(__dirname, '../app/finops/page.tsx'), 'utf8')
    expect(page).toContain(expected)
    const unavailablePanel = page.slice(page.indexOf('{!aiCosts?.available ? ('), page.indexOf('{/* Hero metrics */}'))
    expect(unavailablePanel).toContain(expected)
    expect(unavailablePanel).not.toContain(falseClaim)
  })

  it('keeps valid 24h data when every 7d cost query fails, without reporting a zero total', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const query = decodeURIComponent(String(url))
      if (query.includes('langfuse_up')) return promResult('1')
      return query.includes('[7d]') ? { ok: false } : promResult('2.5')
    }))
    const { GET } = await import('@/app/api/finops/ai-costs/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(true)
    expect(body.agents).toHaveLength(5)
    expect(body.agents.every((agent: { costLast24hUsd: number }) => agent.costLast24hUsd === 2.5)).toBe(true)
    expect(body.agents.every((agent: { costLast7dUsd: number | null }) => agent.costLast7dUsd === null)).toBe(true)
    expect(body.totalCostLast7dUsd).toBeNull()
    expect(body.coverage.lastSuccessfulLoad).toBeNull()
  })

  it('does not present a partial 7d sum as the complete total', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const query = decodeURIComponent(String(url))
      if (query.includes('langfuse_up')) return promResult('1')
      return query.includes('[7d]') && query.includes('copilot') ? emptyResult : promResult('2.5')
    }))
    const { GET } = await import('@/app/api/finops/ai-costs/route')
    const body = await (await GET()).json()

    expect(body.agents.find((agent: { agentId: string }) => agent.agentId === 'finops-agent').costLast7dUsd).toBe(2.5)
    expect(body.totalCostLast7dUsd).toBeNull()
    expect(body.coverage.lastSuccessfulLoad).toBeNull()
  })

  it('keeps measured 7d costs when 24h metrics are missing, with no invented burn rate', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const query = decodeURIComponent(String(url))
      if (query.includes('langfuse_up')) return promResult('1')
      return query.includes('[24h]') ? emptyResult : promResult('2.5')
    }))
    const { GET } = await import('@/app/api/finops/ai-costs/route')
    const body = await (await GET()).json()

    expect(body.agents).toHaveLength(5)
    expect(body.totalCostLast7dUsd).toBe(12.5)
    expect(body.agents.every((agent: { tokensLast24h: number | null; costLast24hUsd: number | null; burnRate: string | null }) =>
      agent.tokensLast24h === null && agent.costLast24hUsd === null && agent.burnRate === null)).toBe(true)
    expect(body.coverage.lastSuccessfulLoad).toBeNull()
  })

  it('does not turn an unavailable 24h cost into zero or a normal burn rate', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const query = decodeURIComponent(String(url))
      if (query.includes('langfuse_up')) return promResult('1')
      return query.includes('[24h]') && query.includes('cost_per_token') ? { ok: false } : promResult('2.5')
    }))
    const { GET } = await import('@/app/api/finops/ai-costs/route')
    const body = await (await GET()).json()

    expect(body.totalCostLast7dUsd).toBe(12.5)
    expect(body.agents.every((agent: { costLast24hUsd: number | null; burnRate: string | null }) =>
      agent.costLast24hUsd === null && agent.burnRate === null)).toBe(true)
    expect(body.coverage.lastSuccessfulLoad).toBeNull()
  })

  it.each(['NaN', '+Inf', '-Inf', '2oops'])('does not treat nonnumeric metric %s as a measured value', async value => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) =>
      decodeURIComponent(String(url)).includes('langfuse_up') ? promResult('1') : promResult(value)))
    const { GET } = await import('@/app/api/finops/ai-costs/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(false)
    expect(body.agents).toEqual([])
    expect(body.totalCostLast7dUsd).toBeNull()
    expect(body.coverage.lastSuccessfulLoad).toBeNull()
  })

  it('preserves measured zero costs and low burn rate instead of marking zero as missing', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) =>
      decodeURIComponent(String(url)).includes('langfuse_up') ? promResult('1') : promResult('0')))
    const { GET } = await import('@/app/api/finops/ai-costs/route')
    const body = await (await GET()).json()

    expect(body.totalCostLast7dUsd).toBe(0)
    expect(body.agents.every((agent: { costLast24hUsd: number; costLast7dUsd: number; burnRate: string }) =>
      agent.costLast24hUsd === 0 && agent.costLast7dUsd === 0 && agent.burnRate === 'low')).toBe(true)
    expect(body.coverage.lastSuccessfulLoad).not.toBeNull()
  })

  it('reports unavailable when the bridge is up but every agent metric query fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) =>
      decodeURIComponent(String(url)).includes('langfuse_up') ? promResult('1') : { ok: false }))
    const { GET } = await import('@/app/api/finops/ai-costs/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(false)
    expect(body.agents).toEqual([])
    expect(body.totalCostLast7dUsd).toBeNull()
    expect(body.coverage.lastSuccessfulLoad).toBeNull()
  })

  it('does not omit a missing agent from the completeness requirement for the 7d total', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const query = decodeURIComponent(String(url))
      if (query.includes('langfuse_up')) return promResult('1')
      return query.includes('copilot') ? emptyResult : promResult('2.5')
    }))
    const { GET } = await import('@/app/api/finops/ai-costs/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(true)
    expect(body.agents).toHaveLength(4)
    expect(body.agents.every((agent: { costLast7dUsd: number }) => agent.costLast7dUsd === 2.5)).toBe(true)
    expect(body.totalCostLast7dUsd).toBeNull()
    expect(body.coverage.lastSuccessfulLoad).toBeNull()
  })

})
