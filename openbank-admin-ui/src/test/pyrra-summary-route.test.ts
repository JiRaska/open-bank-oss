// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'

const response = (rows: { slo: string; value: number }[]) => Response.json({
  status: 'success',
  data: { result: rows.map(row => ({ metric: { slo: row.slo }, value: [1_789_000_000, String(row.value)] })) },
})

describe('Pyrra evidence summary route', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    delete process.env.PROMETHEUS_URL
  })

  it('derives availability and remaining budget from Pyrra recording rules', async () => {
    process.env.PROMETHEUS_URL = 'https://prometheus.test'
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response([{ slo: 'openbank-transaction-availability', value: 10_000 }]))
      .mockResolvedValueOnce(response([{ slo: 'openbank-transaction-availability', value: 2 }]))
    vi.stubGlobal('fetch', fetchMock)

    const { GET } = await import('@/app/api/pyrra/summary/route')
    const result = await GET()

    expect(result.status).toBe(200)
    const body = await result.json()
    expect(body).toMatchObject({ available: true, configured: 6, monitored: 1 })
    expect(body.objectives).toContainEqual(expect.objectContaining({
      name: 'openbank-transaction-availability',
      target: 0.999,
      window: '30d',
      availability: 0.9998,
      budgetRemaining: expect.closeTo(0.8, 10),
      requestCount: 10_000,
    }))
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.every(([url]) => String(url).startsWith('https://prometheus.test/api/v1/query'))).toBe(true)
  })

  it('treats absent error series as zero but does not call zero traffic healthy', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response([
        { slo: 'openbank-ledger-availability', value: 2_500 },
        { slo: 'openbank-fraud-availability', value: 0 },
      ]))
      .mockResolvedValueOnce(response([]))
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/pyrra/summary/route')

    const result = await GET()
    const body = await result.json()
    expect(body.objectives).toContainEqual(expect.objectContaining({
      name: 'openbank-ledger-availability', availability: 1, budgetRemaining: 1,
    }))
    expect(body.objectives).toContainEqual(expect.objectContaining({
      name: 'openbank-fraud-availability', availability: null, budgetRemaining: null, requestCount: 0,
    }))
  })

  it('returns a typed failure instead of fabricating healthy evidence', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const { GET } = await import('@/app/api/pyrra/summary/route')

    const result = await GET()
    expect(result.status).toBe(502)
    await expect(result.json()).resolves.toMatchObject({ available: false, error: 'pyrra_evidence_unreachable', objectives: [] })
  })
})
