// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0076 Layer 1 — BFF route integration tests for the delegation console (ADR-0230/0232),
// mocked upstream + session.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }
const PARTY = '018f4a3c-1b2d-7e00-9a11-000000000001'
const GRANT = '018f4a3c-1b2d-7e00-9a11-000000000002'
const RESOURCE = '018f4a3c-1b2d-7e00-9a11-000000000003'

function grant(id: string, status = 'ACTIVE') {
  return {
    id,
    grantorPartyId: PARTY,
    granteePartyId: '018f4a3c-1b2d-7e00-9a11-0000000000ff',
    resourceType: 'ACCOUNT',
    resourceId: RESOURCE,
    capabilities: ['ACCOUNT_READ_BALANCES'],
    validFrom: '2026-01-01T00:00:00Z',
    validTo: null,
    status,
  }
}

function fetchCalls() {
  return vi.mocked(global.fetch).mock.calls as unknown as [string, RequestInit][]
}

beforeEach(() => {
  vi.resetModules()
  vi.mocked(auth).mockResolvedValue(SESSION as never)
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('GET /api/delegations/party/[partyId]', () => {
  async function call(partyId: string) {
    const { GET } = await import('@/app/api/delegations/party/[partyId]/route')
    return GET({} as never, { params: Promise.resolve({ partyId }) })
  }

  it('401s without a session and never calls upstream', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const spy = vi.fn()
    vi.stubGlobal('fetch', spy)
    const res = await call(PARTY)
    expect(res.status).toBe(401)
    expect(spy).not.toHaveBeenCalled()
  })

  it('rejects a non-UUID party id before any upstream call', async () => {
    const spy = vi.fn()
    vi.stubGlobal('fetch', spy)
    const res = await call('not-a-uuid')
    expect(res.status).toBe(400)
    expect(spy).not.toHaveBeenCalled()
  })

  it('fans out to both directions, relays the operator bearer, and omits the customer IDOR header', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
      Promise.resolve(new Response(JSON.stringify([grant(url.includes('grantor') ? 'g1' : 'r1')]), { status: 200 })),
    ))

    const res = await call(PARTY)
    const body = await res.json()

    expect(res.status).toBe(200)
    expect(body.granted[0].id).toBe('g1')
    expect(body.received[0].id).toBe('r1')
    expect(body.sources).toEqual({ granted: 'ok', received: 'ok' })

    const urls = fetchCalls().map(([u]) => String(u))
    expect(urls.some(u => u.includes(`/api/v1/delegations/grantor/${PARTY}`))).toBe(true)
    expect(urls.some(u => u.includes(`/api/v1/delegations/grantee/${PARTY}`))).toBe(true)

    for (const [, init] of fetchCalls()) {
      const h = new Headers(init.headers)
      expect(h.get('authorization')).toBe('Bearer operator-token')
      // Operator calls must NOT carry customer-edge's party-scoping header — it would make
      // delegation-service refuse every party except the one we stamped.
      expect(h.get('X-Customer-Party-Id')).toBeNull()
    }
  })

  it('reports a refused direction as forbidden rather than an empty list', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
      Promise.resolve(
        String(url).includes('grantor')
          ? new Response('{}', { status: 403 })
          : new Response(JSON.stringify([grant('r1')]), { status: 200 }),
      ),
    ))

    const body = await (await call(PARTY)).json()
    expect(body.granted).toEqual([])
    expect(body.sources.granted).toBe('forbidden')
    expect(body.sources.received).toBe('ok')
  })

  it('marks an unreachable direction unavailable, distinct from refused', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
      String(url).includes('grantor')
        ? Promise.reject(new Error('ECONNREFUSED'))
        : Promise.resolve(new Response('{}', { status: 500 })),
    ))

    const body = await (await call(PARTY)).json()
    expect(body.sources).toEqual({ granted: 'unavailable', received: 'unavailable' })
  })

  it('survives a non-array upstream body instead of leaking it to the table', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ oops: true }), { status: 200 })))
    const body = await (await call(PARTY)).json()
    expect(body.granted).toEqual([])
    expect(body.received).toEqual([])
  })
})

describe('GET /api/delegations/effective-access/[partyId]', () => {
  async function call(partyId: string) {
    const { GET } = await import('@/app/api/delegations/effective-access/[partyId]/route')
    return GET({} as never, { params: Promise.resolve({ partyId }) })
  }

  it('assembles owned resources, received grants and role presets from their authoritative services', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const url = String(input)
      if (url.includes('/accounts?')) return new Response(JSON.stringify({ data: [{ id: 'a1', accountNumber: '1234' }] }), { status: 200 })
      if (url.includes(`/accounts/${RESOURCE}`)) return new Response(JSON.stringify({ id: RESOURCE, accountNumber: 'CZ1234567890', currencyCode: 'CZK' }), { status: 200 })
      if (url.includes('/cards/party/')) return new Response(JSON.stringify([{ id: 'c1', maskedPan: '•••• 4321' }]), { status: 200 })
      if (url.includes('/delegations/grantee/')) return new Response(JSON.stringify([grant('r1')]), { status: 200 })
      return new Response(JSON.stringify([{ id: 'p1', name: 'Účetní', resourceType: 'ACCOUNT', capabilities: ['ACCOUNT_READ_BALANCES'] }]), { status: 200 })
    }))

    const response = await call(PARTY)
    const body = await response.json()
    expect(body.accounts[0].id).toBe('a1')
    expect(body.cards[0].id).toBe('c1')
    expect(body.grants[0].id).toBe('r1')
    expect(Number.isNaN(Date.parse(body.evaluatedAt))).toBe(false)
    expect(body.nextChangeAt).toBeNull()
    expect(body.refreshAfterMs).toBeNull()
    expect(body.presets[0].name).toBe('Účetní')
    expect(body.resourceDetails[0]).toMatchObject({ key: `ACCOUNT:${RESOURCE}`, state: 'ok', detail: { accountNumber: 'CZ1234567890' } })
    expect(body.sources).toEqual({ accounts: 'ok', cards: 'ok', grants: 'ok', presets: 'ok' })
    expect(fetchCalls()).toHaveLength(5)
  })

  it('uses the BFF clock and resolves details only inside a valid interval', async () => {
    const futureResource = '018f4a3c-1b2d-7e00-9a11-000000000004'
    const expiredResource = '018f4a3c-1b2d-7e00-9a11-000000000005'
    const malformedResource = '018f4a3c-1b2d-7e00-9a11-000000000006'
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const url = String(input)
      if (url.includes('/delegations/grantee/')) {
        return new Response(JSON.stringify([
          grant('effective'),
          { ...grant('future'), resourceId: futureResource, validFrom: '2099-01-01T00:00:00Z' },
          { ...grant('expired'), resourceId: expiredResource, validFrom: '2000-01-01T00:00:00Z', validTo: '2001-01-01T00:00:00Z' },
          { ...grant('malformed'), resourceId: malformedResource, validFrom: '' },
        ]), { status: 200 })
      }
      return new Response('[]', { status: 200 })
    }))

    const body = await (await call(PARTY)).json()
    expect(body.grants.map((item: { id: string }) => item.id)).toEqual(['effective', 'future', 'expired', 'malformed'])
    expect(body.nextChangeAt).toBe('2099-01-01T00:00:00.000Z')
    expect(body.refreshAfterMs).toBeGreaterThan(0)
    const resolvedResources = fetchCalls().map(([url]) => String(url)).filter(url => /\/api\/v1\/accounts\/[0-9a-f-]+$/.test(url))
    expect(resolvedResources).toEqual([expect.stringContaining(`/api/v1/accounts/${RESOURCE}`)])
  })

  it('requests an immediate refresh when a validity boundary passes during detail resolution', async () => {
    vi.useFakeTimers()
    vi.setSystemTime('2026-09-01T12:00:00Z')
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const url = String(input)
      if (url.includes('/delegations/grantee/')) {
        return new Response(JSON.stringify([{ ...grant('ending'), validTo: '2026-09-01T12:00:01Z' }]), { status: 200 })
      }
      if (url.includes(`/accounts/${RESOURCE}`)) {
        vi.setSystemTime('2026-09-01T12:00:02Z')
        return new Response(JSON.stringify({ id: RESOURCE }), { status: 200 })
      }
      return new Response('[]', { status: 200 })
    }))

    const body = await (await call(PARTY)).json()
    expect(body.evaluatedAt).toBe('2026-09-01T12:00:00.000Z')
    expect(body.nextChangeAt).toBe('2026-09-01T12:00:01.000Z')
    expect(body.refreshAfterMs).toBe(0)
  })

  it('keeps successful ownership visible when another source is forbidden', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const url = String(input)
      if (url.includes('/cards/party/')) return new Response('{}', { status: 403 })
      if (url.includes('/accounts?')) return new Response(JSON.stringify([{ id: 'a1' }]), { status: 200 })
      return new Response('[]', { status: 200 })
    }))

    const body = await (await call(PARTY)).json()
    expect(body.accounts).toEqual([{ id: 'a1' }])
    expect(body.cards).toEqual([])
    expect(body.sources.cards).toBe('forbidden')
    expect(body.sources.accounts).toBe('ok')
  })

  it('keeps the grant visible when its concrete resource detail is forbidden', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const url = String(input)
      if (url.includes('/delegations/grantee/')) return new Response(JSON.stringify([grant('r1')]), { status: 200 })
      if (url.includes(`/accounts/${RESOURCE}`)) return new Response('{}', { status: 403 })
      return new Response('[]', { status: 200 })
    }))

    const body = await (await call(PARTY)).json()
    expect(body.grants).toHaveLength(1)
    expect(body.resourceDetails[0]).toMatchObject({ key: `ACCOUNT:${RESOURCE}`, state: 'forbidden' })
  })
})

describe('GET /api/delegations/[id]', () => {
  async function call(id: string) {
    const { GET } = await import('@/app/api/delegations/[id]/route')
    return GET({} as never, { params: Promise.resolve({ id }) })
  }

  it('401s without a session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    vi.stubGlobal('fetch', vi.fn())
    expect((await call(GRANT)).status).toBe(401)
  })

  it('rejects a non-UUID id before any upstream call', async () => {
    const spy = vi.fn()
    vi.stubGlobal('fetch', spy)
    expect((await call('../../etc/passwd')).status).toBe(400)
    expect(spy).not.toHaveBeenCalled()
  })

  it('returns the grant and relays the bearer', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(grant(GRANT)), { status: 200 })))
    const res = await call(GRANT)
    expect(res.status).toBe(200)
    expect((await res.json()).id).toBe(GRANT)
    const [url, init] = fetchCalls()[0]
    expect(String(url)).toContain(`/api/v1/delegations/${GRANT}`)
    expect(new Headers(init.headers).get('authorization')).toBe('Bearer operator-token')
  })

  it('passes a 404 through as not_found', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 404 })))
    const res = await call(GRANT)
    expect(res.status).toBe(404)
    expect((await res.json()).error).toBe('not_found')
  })

  it('never leaks an upstream error body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ stack: 'internal detail', sql: 'select *' }), { status: 500 }),
    ))
    const res = await call(GRANT)
    const body = await res.json()
    expect(res.status).toBe(502)
    expect(body).toEqual({ error: 'upstream_error' })
  })
})

describe('POST /api/delegations/check', () => {
  async function call(body: unknown) {
    const { POST } = await import('@/app/api/delegations/check/route')
    return POST(new Request('http://localhost/api/delegations/check', {
      method: 'POST',
      body: JSON.stringify(body),
    }))
  }

  const valid = {
    granteePartyId: PARTY,
    resourceType: 'ACCOUNT',
    resourceId: RESOURCE,
    capability: 'ACCOUNT_INITIATE_PAYMENT',
  }

  it('401s without a session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    vi.stubGlobal('fetch', vi.fn())
    expect((await call(valid)).status).toBe(401)
  })

  it('rejects a malformed body before any upstream call', async () => {
    const spy = vi.fn()
    vi.stubGlobal('fetch', spy)
    expect((await call({ ...valid, granteePartyId: 'nope' })).status).toBe(400)
    expect((await call({ resourceType: 'ACCOUNT' })).status).toBe(400)
    expect(spy).not.toHaveBeenCalled()
  })

  it('forwards a re-built body, dropping fields the caller smuggled in', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ granted: false, code: 'CEILING_EXCEEDED' }), { status: 200 }),
    ))

    const res = await call({ ...valid, amount: { amount: 500, currency: 'CZK' }, grantorPartyId: 'attacker-controlled' })
    expect(res.status).toBe(200)
    expect((await res.json()).code).toBe('CEILING_EXCEEDED')

    const [url, init] = fetchCalls()[0]
    expect(String(url)).toContain('/api/v1/delegations/check')
    expect(init.method).toBe('POST')
    const sent = JSON.parse(String(init.body))
    expect(sent).toEqual({ ...valid, amount: { amount: 500, currency: 'CZK' } })
    expect(sent.grantorPartyId).toBeUndefined()
  })

  it('drops a malformed amount rather than forwarding it', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ granted: true }), { status: 200 })))
    await call({ ...valid, amount: { amount: 'lots', currency: 'CZK' } })
    expect(JSON.parse(String(fetchCalls()[0][1].body)).amount).toBeUndefined()
  })
})

describe('GET /api/delegations/projection-health', () => {
  async function call() {
    const { GET } = await import('@/app/api/delegations/projection-health/route')
    return GET()
  }

  it('401s without a session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    vi.stubGlobal('fetch', vi.fn())
    expect((await call()).status).toBe(401)
  })

  it('derives the consumer set from the topic, never a hardcoded service list', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (String(url).endsWith('/api/clusters')) {
        return Promise.resolve(new Response(JSON.stringify([{ name: 'openbank' }]), { status: 200 }))
      }
      return Promise.resolve(new Response(JSON.stringify([
        { groupId: 'card-issuance-delegation', state: 'STABLE', consumerLag: 7, members: 1 },
        { groupId: 'account-service-delegation', state: 'STABLE', consumerLag: 0, members: 2 },
      ]), { status: 200 }))
    }))

    const body = await (await call()).json()
    expect(body.state).toBe('ok')
    expect(body.consumers.map((c: { groupId: string }) => c.groupId))
      .toEqual(['account-service-delegation', 'card-issuance-delegation'])
    expect(body.consumers[1].lag).toBe(7)

    const urls = fetchCalls().map(([u]) => String(u))
    expect(urls.some(u => u.includes('openbank.delegation.events') && u.includes('consumer-groups'))).toBe(true)
  })

  it('says "unknown", never an empty healthy-looking table, when Kafka UI cannot answer', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
      String(url).endsWith('/api/clusters')
        ? Promise.resolve(new Response(JSON.stringify([{ name: 'openbank' }]), { status: 200 }))
        : Promise.resolve(new Response('{}', { status: 404 })),
    ))

    const body = await (await call()).json()
    expect(body.state).toBe('unavailable')
    expect(body.consumers).toEqual([])
  })

  it('degrades to unavailable when Kafka UI is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')))
    const body = await (await call()).json()
    expect(body.state).toBe('unavailable')
  })
})
