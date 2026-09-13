// SPDX-License-Identifier: Apache-2.0

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { auth } from '@/auth'
import { DISPUTE_STATUSES } from '@/lib/disputes/disputePortfolio'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

const row = (status: string, id = `id-${status}`) => ({
  id, reference: `DSP-${status}`, disputeType: 'UNAUTHORIZED', status,
  accountId: 'account-1', transactionId: 'transaction-1', amount: 10, currency: 'EUR',
  resolutionDeadline: '2026-10-01', createdAt: '2026-09-01T10:00:00Z',
})

beforeEach(() => {
  vi.resetModules()
  process.env.KUBERNETES_SERVICE_HOST = 'true'
  vi.mocked(auth).mockResolvedValue({
    user: { accessToken: 'test-token', roles: ['ROLE_COMPLIANCE'] },
  } as never)
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  delete process.env.KUBERNETES_SERVICE_HOST
})

describe('GET /api/disputes', () => {
  it('refuses an unauthenticated request before contacting the service', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/disputes/route')

    const response = await GET()

    expect(response.status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('loads every real status in parallel and de-duplicates a transitioning case', async () => {
    const sharedId = 'transitioning-case'
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const status = new URL(String(url)).searchParams.get('status')!
      expect(init?.headers).toMatchObject({ Authorization: 'Bearer test-token' })
      const id = status === 'OPEN' || status === 'UNDER_REVIEW' ? sharedId : `id-${status}`
      return new Response(JSON.stringify([row(status, id)]), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/disputes/route')

    const response = await GET()
    const body = await response.json() as Array<{ id: string; status: string }>

    expect(response.status).toBe(200)
    expect(fetchMock).toHaveBeenCalledTimes(DISPUTE_STATUSES.length)
    expect(String(fetchMock.mock.calls[0][0])).toContain('dispute-service.dispute.svc:8135')
    expect(body).toHaveLength(DISPUTE_STATUSES.length - 1)
    expect(body.find(item => item.id === sharedId)?.status).toBe('UNDER_REVIEW')
  })

  it('fails the portfolio closed when any status page is invalid', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string | URL | Request) => {
      const status = new URL(String(url)).searchParams.get('status')!
      return new Response(JSON.stringify(status === 'ESCALATED' ? [row('CLOSED')] : [row(status)]), { status: 200 })
    }))
    const { GET } = await import('@/app/api/disputes/route')

    const response = await GET()

    expect(response.status).toBe(502)
    await expect(response.json()).resolves.toEqual({ error: 'invalid_upstream_response' })
  })

  it.each([401, 403])('preserves upstream access loss as HTTP %s', async statusCode => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: statusCode })))
    const { GET } = await import('@/app/api/disputes/route')

    const response = await GET()

    expect(response.status).toBe(statusCode)
    await expect(response.json()).resolves.toEqual({
      error: statusCode === 401 ? 'unauthorized' : 'forbidden',
    })
  })
})
