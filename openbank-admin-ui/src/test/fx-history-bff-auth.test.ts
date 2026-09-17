import { beforeEach, describe, expect, it, vi } from 'vitest'

const auth = vi.fn()
vi.mock('@/auth', () => ({ auth }))
vi.mock('@/lib/discovery', () => ({
  inCluster: () => false,
  resolveInClusterBaseUrl: vi.fn(),
}))

const context = { params: Promise.resolve({ base: 'eur', quote: 'czk' }) }

describe('FX history BFF authentication', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
    vi.unstubAllGlobals()
  })

  it('rejects an unauthenticated browser before contacting fx-service', async () => {
    auth.mockResolvedValue(null)
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const { GET } = await import('@/app/api/fx/history/[base]/[quote]/route')

    const response = await GET(new Request('http://localhost/api/fx/history/eur/czk'), context)

    expect(response.status).toBe(401)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('relays the operator bearer token to the RBAC-protected history endpoint', async () => {
    auth.mockResolvedValue({ user: { accessToken: 'operator-token' } })
    const fetch = vi.fn().mockResolvedValue(new Response('[]', { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    const { GET } = await import('@/app/api/fx/history/[base]/[quote]/route')

    const response = await GET(new Request('http://localhost/api/fx/history/eur/czk'), context)

    expect(response.status).toBe(200)
    expect(fetch).toHaveBeenCalledOnce()
    expect(fetch.mock.calls[0][1]).toMatchObject({
      headers: { Accept: 'application/json', Authorization: 'Bearer operator-token' },
    })
    expect(fetch.mock.calls[0][0]).toContain('/api/v1/fx/rates/EUR/CZK/history?source=CNB&limit=100')
  })

  it('requests the same clamped three-calendar-month window as the customer app at month end', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-31T12:00:00.000Z'))
    try {
      auth.mockResolvedValue({ user: { accessToken: 'operator-token' } })
      const fetch = vi.fn().mockResolvedValue(new Response('[]', { status: 200 }))
      vi.stubGlobal('fetch', fetch)
      const { GET } = await import('@/app/api/fx/history/[base]/[quote]/route')

      expect((await GET(new Request('http://localhost/api/fx/history/eur/czk'), context)).status).toBe(200)
      const upstream = new URL(fetch.mock.calls[0][0])
      expect(upstream.searchParams.get('from')).toBe('2026-02-28T12:00:00.000Z')
      expect(upstream.searchParams.get('to')).toBe('2026-05-31T12:00:00.000Z')
    } finally {
      vi.useRealTimers()
    }
  })
})
