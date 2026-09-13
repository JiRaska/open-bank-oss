// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'

const objective = (name: string) => ({
  labels: { __name__: name },
  target: 0.999,
  window: '2592000s',
  description: `${name} availability`,
})

describe('Pyrra summary route', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    delete process.env.PYRRA_URL
  })

  it('returns a bounded customer-journey summary from Pyrra Connect RPC', async () => {
    process.env.PYRRA_URL = 'http://pyrra.test/tools/pyrra'
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/List')) {
        return Response.json({ objectives: [
          objective('openbank-transaction-availability'),
          objective('unrelated-service-availability'),
        ] })
      }
      expect(url).toBe('http://pyrra.test/tools/pyrra/objectives.v1alpha1.ObjectiveService/GetStatus')
      expect(init?.headers).toMatchObject({ 'Connect-Protocol-Version': '1' })
      expect(JSON.parse(String(init?.body))).toEqual({ expr: '{__name__="openbank-transaction-availability"}' })
      return Response.json({ status: [{
        availability: { percentage: 0.9998, total: 12_500, errors: 2.5 },
        budget: { remaining: 0.8 },
      }] })
    })
    vi.stubGlobal('fetch', fetchMock)

    const { GET } = await import('@/app/api/pyrra/summary/route')
    const response = await GET()

    expect(response.status).toBe(200)
    await expect(response.json()).resolves.toMatchObject({
      available: true,
      configured: 2,
      monitored: 1,
      objectives: [{
        name: 'openbank-transaction-availability',
        budgetRemaining: 0.8,
        availability: 0.9998,
        requestCount: 12_500,
      }],
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('distinguishes a reachable objective without samples from an unreachable Pyrra API', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ objectives: [objective('openbank-ledger-availability')] }))
      .mockResolvedValueOnce(Response.json({ status: [] }))
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/pyrra/summary/route')

    const noSamples = await GET()
    await expect(noSamples.json()).resolves.toMatchObject({
      available: true,
      objectives: [{ name: 'openbank-ledger-availability', budgetRemaining: null, availability: null }],
    })

    fetchMock.mockReset().mockRejectedValue(new Error('offline'))
    const offline = await GET()
    expect(offline.status).toBe(502)
    await expect(offline.json()).resolves.toMatchObject({ available: false, error: 'pyrra_unreachable' })
  })
})
