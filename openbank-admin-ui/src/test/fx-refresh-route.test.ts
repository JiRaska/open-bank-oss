import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'

const auth = vi.fn()
vi.mock('@/auth', () => ({ auth }))

const request = (source: unknown) => new NextRequest('http://localhost/api/fx/refresh', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ source }),
})

describe('FX external rate fetch authorization', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
    vi.unstubAllGlobals()
  })

  it('does not contact external rate sources without a session', async () => {
    auth.mockResolvedValue(null)
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const { POST } = await import('@/app/api/fx/refresh/route')

    expect((await POST(request('all'))).status).toBe(401)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('does not contact external rate sources without FX access', async () => {
    auth.mockResolvedValue({ user: { roles: ['ROLE_COMPLIANCE'] } })
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const { POST } = await import('@/app/api/fx/refresh/route')

    expect((await POST(request('all'))).status).toBe(403)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('rejects an unknown source before any external request', async () => {
    auth.mockResolvedValue({ user: { roles: ['ROLE_OPERATOR'] } })
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const { POST } = await import('@/app/api/fx/refresh/route')

    expect((await POST(request('other'))).status).toBe(400)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('reports the actual outcome of the requested source only', async () => {
    auth.mockResolvedValue({ user: { roles: ['ROLE_OPERATOR'] } })
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ rates: [{ currencyCode: 'USD' }] }), { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    const { POST } = await import('@/app/api/fx/refresh/route')

    const response = await POST(request('cnb'))
    expect(response.status).toBe(200)
    expect((await response.json()).results).toMatchObject({ cnb: { ok: true, count: 1 } })
    expect(fetch).toHaveBeenCalledOnce()
    expect(fetch.mock.calls[0][0]).toContain('api.cnb.cz')
  })
})
