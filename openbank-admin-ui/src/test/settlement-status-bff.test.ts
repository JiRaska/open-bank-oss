// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'
import { GET } from '@/app/api/settlements/[id]/route'

const id = 'bde18d9e-3e49-4f4e-98cc-a56646ccbc61'
const context = { params: Promise.resolve({ id }) }
const request = new Request(`http://localhost/api/settlements/${id}`, { headers: { Authorization: 'Bearer forged-browser-token' } })
const detail = {
  id, payerAccountId: 'd52f0505-bb8a-4a9f-b8e0-9c9c5f765876', payeeAccountId: 'ab72c875-9e17-4491-a5ef-0bdb34946a3b',
  amount: '999999999999999.9900', currency: 'CZK', status: 'BALANCE_STATE_UNKNOWN',
  createdAt: '2026-09-26T10:00:00Z', updatedAt: '2026-09-26T10:01:00Z',
}
beforeEach(() => { vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'session-token', roles: ['ROLE_OPERATOR'] } } as never) })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('settlement status BFF', () => {
  it('uses only the session bearer, preserves exact decimals and prohibits caching', async () => {
    const fetcher = vi.fn().mockResolvedValue(Response.json(detail))
    vi.stubGlobal('fetch', fetcher)
    const response = await GET(request, context)
    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.json()).toEqual(detail)
    const [url, init] = fetcher.mock.calls[0]
    expect(url).toContain(`:8138/api/v1/settlements/${id}`)
    expect(new Headers(init.headers).get('authorization')).toBe('Bearer session-token')
    expect(init.cache).toBe('no-store')
    expect(init.body).toBeUndefined()
    expect(init.method).toBeUndefined()
  })

  it('rejects absent sessions, wrong roles and malformed IDs before fetching', async () => {
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    vi.mocked(auth).mockResolvedValue(null as never)
    expect((await GET(request, context)).status).toBe(401)
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'token', roles: ['ROLE_VIEWER'] } } as never)
    expect((await GET(request, context)).status).toBe(403)
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'token', roles: ['ROLE_ADMIN'] } } as never)
    expect((await GET(request, { params: Promise.resolve({ id: '../approvals' }) })).status).toBe(400)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it.each([400, 401, 403, 404, 500])('preserves safe error meaning without leaking details (%i)', async upstreamStatus => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('private upstream detail', { status: upstreamStatus })))
    const response = await GET(request, context)
    expect(response.status).toBe(upstreamStatus === 500 ? 502 : upstreamStatus)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.text()).not.toContain('private upstream detail')
  })

  it.each([
    { ...detail, id: detail.payerAccountId },
    { ...detail, amount: 999999999999999.99 },
    { ...detail, status: 'EXECUTED' },
    { ...detail, status: 'invented' },
  ])('fails closed on a mismatched or malformed financial result', async body => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(body)))
    expect((await GET(request, context)).status).toBe(502)
  })

  it('returns unavailable after a single failed read without retrying a write', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('connection lost'))
    vi.stubGlobal('fetch', fetcher)
    expect((await GET(request, context)).status).toBe(502)
    expect(fetcher).toHaveBeenCalledOnce()
  })
})
