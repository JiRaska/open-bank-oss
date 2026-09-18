// SPDX-License-Identifier: Apache-2.0

import { beforeEach, describe, expect, it, vi } from 'vitest'

const authMock = vi.fn()
vi.mock('@/auth', () => ({ auth: authMock }))

const PARTY = '11111111-1111-4111-8111-111111111111'
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'content-type': 'application/json' },
})

describe('Customer graph live overlay route', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllEnvs()
    authMock.mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } })
  })

  it('fans out in parallel with the human bearer and strips notification content', async () => {
    vi.stubEnv('KUBERNETES_SERVICE_HOST', 'kubernetes.default.svc')
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(new Headers(init?.headers).get('authorization')).toBe('Bearer operator-token')
      const url = String(input)
      if (url.includes(':8100/')) return response({ data: [{
        id: 'account-1', accountNumber: 'CZ12', accountType: 'CURRENT', productId: 'product-1',
        currencyCode: 'CZK', status: 'ACTIVE', openedAt: '2026-01-01T00:00:00Z',
      }] })
      if (url.includes(':8118/')) return response([{ id: 'card-1', accountId: 'account-1', status: 'BLOCKED' }])
      if (url.includes(':8112/') && url.includes('/notifications')) return response({ items: [{
        id: 'notification-1', channel: 'PUSH', template: 'SCA_APPROVAL', status: 'SENT',
        createdAt: '2026-01-02T00:00:00Z', recipient: 'secret@example.test', body: 'secret body',
      }] })
      if (url.includes(':8112/') && url.includes('/devices')) return response({ items: [{
        id: 'device-1', platform: 'IOS', status: 'ACTIVE', registeredAt: '2026-01-01T00:00:00Z',
        token: 'must-not-leak', appInstance: 'must-not-leak',
      }] })
      if (url.includes(':8126/')) return response([{ id: 'application-1', status: 'APPROVED', productKind: 'UNSECURED' }])
      if (url.includes(':8117/')) return response([{ id: 'case-1', status: 'CLEARED', alertDetail: 'must-not-leak' }])
      if (url.includes(':8143/')) return response([{
        id: 'document-1', templateCode: 'AGREEMENT', status: 'GENERATED', storageKey: 'must-not-leak', sha256: 'must-not-leak',
      }])
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/customer-360/[partyId]/graph/route')
    const result = await GET(new Request('http://localhost'), { params: Promise.resolve({ partyId: PARTY }) })
    const body = await result.json()

    expect(result.status).toBe(200)
    expect(fetchMock).toHaveBeenCalledTimes(7)
    expect(fetchMock.mock.calls.map(([url]) => String(url))).toContain(
      `http://card-issuance-service.payments.svc:8118/api/v1/cards/party/${PARTY}?limit=51`,
    )
    expect(fetchMock.mock.calls.map(([url]) => String(url))).toContain(
      `http://lending-service.lending.svc:8126/api/v1/lending/applications?partyId=${PARTY}&limit=31`,
    )
    expect(body.unavailable).toEqual([])
    expect(body.truncated).toEqual([])
    expect(body.accounts).toHaveLength(1)
    expect(body.cards).toHaveLength(1)
    expect(body.notifications).toEqual([expect.objectContaining({ id: 'notification-1', template: 'SCA_APPROVAL' })])
    expect(body.lendingApplications).toHaveLength(1)
    expect(body.amlCases).toHaveLength(1)
    expect(body.devices).toHaveLength(1)
    expect(body.documents).toHaveLength(1)
    expect(JSON.stringify(body)).not.toContain('secret@example.test')
    expect(JSON.stringify(body)).not.toContain('secret body')
    expect(JSON.stringify(body)).not.toContain('must-not-leak')
  })

  it('returns a partial graph when one owning service is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes(':8118/')) return response({}, 503)
      if (url.includes(':8100/')) return response({ data: [] })
      if (url.includes('/notifications') || url.includes('/devices')) return response({ items: [] })
      return response([])
    }))
    const { GET } = await import('@/app/api/customer-360/[partyId]/graph/route')
    const result = await GET(new Request('http://localhost'), { params: Promise.resolve({ partyId: PARTY }) })
    expect(await result.json()).toMatchObject({ cards: [], unavailable: ['cards'] })
  })

  it('returns only the visible slice and marks larger card and lending histories', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes(':8118/')) return response(Array.from({ length: 51 }, (_, index) => ({
        id: `card-${index}`, accountId: 'account-1', status: 'ACTIVE',
      })))
      if (url.includes(':8126/')) return response(Array.from({ length: 31 }, (_, index) => ({
        id: `application-${index}`, status: 'APPROVED',
      })))
      if (url.includes(':8100/')) return response({ data: [] })
      if (url.includes('/notifications') || url.includes('/devices')) return response({ items: [] })
      return response([])
    }))
    const { GET } = await import('@/app/api/customer-360/[partyId]/graph/route')
    const result = await GET(new Request('http://localhost'), { params: Promise.resolve({ partyId: PARTY }) })
    const body = await result.json()
    expect(body.cards).toHaveLength(50)
    expect(body.lendingApplications).toHaveLength(30)
    expect(body.truncated).toEqual(['cards', 'lending'])
  })

  it('denies unauthenticated access before any source call', async () => {
    authMock.mockResolvedValue(null)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/customer-360/[partyId]/graph/route')
    const result = await GET(new Request('http://localhost'), { params: Promise.resolve({ partyId: PARTY }) })
    expect(result.status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('denies a role without Customer 360 permission before any source call', async () => {
    authMock.mockResolvedValue({ user: { accessToken: 'customer-token', roles: ['ROLE_CUSTOMER'] } })
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/customer-360/[partyId]/graph/route')
    const result = await GET(new Request('http://localhost'), { params: Promise.resolve({ partyId: PARTY }) })
    expect(result.status).toBe(403)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('deduplicates simultaneous graph reads from sibling panels', async () => {
    let resolveResponse!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>(resolve => { resolveResponse = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const { loadCustomerGraphFacts } = await import('@/lib/context/customerGraphClient')
    const first = loadCustomerGraphFacts(PARTY)
    const second = loadCustomerGraphFacts(PARTY)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    resolveResponse(response({ accounts: [], unavailable: [] }))
    await expect(Promise.all([first, second])).resolves.toHaveLength(2)
  })

  it('retains one settled snapshot for every panel in the current party selection', async () => {
    const selectedParty = '22222222-2222-4222-8222-222222222222'
    const fetchMock = vi.fn(async () => response({ accounts: [], unavailable: [] }))
    vi.stubGlobal('fetch', fetchMock)
    const { clearSelectedCustomerGraphFacts, loadCustomerGraphFacts, selectCustomerGraphFacts } = await import('@/lib/context/customerGraphClient')

    await selectCustomerGraphFacts(selectedParty)
    await loadCustomerGraphFacts(selectedParty)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    clearSelectedCustomerGraphFacts(selectedParty)
  })
})
