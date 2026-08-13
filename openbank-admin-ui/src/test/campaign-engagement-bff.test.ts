// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn(async () => ({ user: { accessToken: 'token' } })) }))
vi.mock('@/lib/services/bff', () => ({
  serverSvcUrl: (_service: string, _name: string, _port: number, path: string) => `http://campaign${path}`,
}))

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('campaign engagement BFF', () => {
  it('joins privacy-safe ClickHouse aggregates without changing campaign-service semantics', async () => {
    const campaignId = '11111111-1111-1111-1111-111111111111'
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === 'http://campaign/api/v1/campaigns') {
        return Response.json([{ id: campaignId, name: 'Push offer' }])
      }
      if (url.endsWith('/api/v1/campaigns/summary')) return Response.json([])
      if (url.startsWith('http://localhost:8123')) {
        return Response.json({
          data: [{
            campaign_id: campaignId,
            impressions: '120',
            clicks: '18',
            dismissals: '4',
            first_observed_at: '2026-08-13 10:00:00.000',
            last_observed_at: '2026-08-13 11:00:00.000',
          }],
        })
      }
      return new Response('', { status: 404 })
    }))

    const { GET } = await import('@/app/api/campaigns/route')
    const response = await GET()
    const body = await response.json()

    expect(body.state).toBe('ok')
    expect(body.engagement).toEqual({
      state: 'ok',
      items: [{
        campaignId,
        impressions: 120,
        clicks: 18,
        dismissals: 4,
        firstObservedAt: '2026-08-13 10:00:00.000',
        lastObservedAt: '2026-08-13 11:00:00.000',
      }],
    })
  })

  it('keeps the campaign list readable when analytics is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === 'http://campaign/api/v1/campaigns') return Response.json([{ id: 'c-1' }])
      if (url.endsWith('/api/v1/campaigns/summary')) return Response.json([])
      if (url.startsWith('http://localhost:8123')) return new Response('missing view', { status: 500 })
      return new Response('', { status: 404 })
    }))

    const { GET } = await import('@/app/api/campaigns/route')
    const body = await (await GET()).json()

    expect(body.state).toBe('ok')
    expect(body.items).toEqual([{ id: 'c-1' }])
    expect(body.engagement).toEqual({ state: 'unavailable', items: [] })
  })
})
