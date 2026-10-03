// SPDX-License-Identifier: Apache-2.0
import path from 'node:path'
import { PactV3, SpecificationVersion } from '@pact-foundation/pact'
import { NextRequest } from 'next/server'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
vi.mock('@/lib/context/server', () => ({ contextServiceUrl: vi.fn() }))
import { auth } from '@/auth'
import { contextServiceUrl } from '@/lib/context/server'
import { GET as getAml } from '@/app/api/context/aml-cases/[id]/route'
import { GET as getAuthority } from '@/app/api/context/authorizations/[id]/route'
import { GET } from '@/app/api/context/incidents/[reference]/impact/route'

function createPact() {
  return new PactV3({
      consumer: 'openbank-admin-ui', provider: 'openbank-context-service',
      dir: path.resolve(process.cwd(), '../pacts'), host: '127.0.0.1',
      spec: SpecificationVersion.SPECIFICATION_VERSION_V3, logLevel: 'error',
  })
}

describe('Admin UI incident impact consumer contract', () => {
  afterEach(() => vi.restoreAllMocks())

  it.each([
    { status: 'MISSING', counts: {}, total: 0 },
    { status: 'PARTIAL', counts: { SERVICE: 2 }, total: 2 },
    { status: 'AVAILABLE', counts: { SERVICE: 2 }, total: 2 },
  ])('preserves $status projection semantics through the real BFF', async ({ status, counts, total }) => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'pact-operator', roles: ['ROLE_OPERATOR'] } } as never)
    const pact = createPact()
    const impact = { affectedByType: counts, total, drilldownAvailable: false, projectionStatus: status }
    await pact.given(`an authorized incident projection is ${status.toLowerCase()}`)
      .uponReceiving(`read ${status.toLowerCase()} incident impact from Customer 360 admin`)
      .withRequest({
        method: 'GET', path: '/api/v1/context/incidents/incident-1/impact',
        headers: {
          Authorization: 'Bearer pact-operator', Accept: 'application/json',
          'X-Investigation-Case-Id': 'case-1', 'X-Investigation-Purpose': 'INCIDENT_IMPACT',
        },
      })
      .willRespondWith({
        status: 200, headers: { 'Content-Type': 'application/json' },
        body: { incidentRef: 'incident-1', ...impact },
      })
      .executeTest(async server => {
        vi.mocked(contextServiceUrl).mockImplementation(route => `${server.url}${route}`)
        const response = await GET(new NextRequest('http://localhost/api/context/incidents/incident-1/impact?caseId=case-1&purpose=INCIDENT_IMPACT'), {
          params: Promise.resolve({ reference: 'incident-1' }),
        })
        expect(response.status).toBe(200)
        expect(response.headers.get('Cache-Control')).toBe('no-store')
        expect(await response.json()).toEqual(impact)
      })
  })

  it('rejects an operator accessing an unassigned case', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'pact-operator', roles: ['ROLE_OPERATOR'] } } as never)
    await createPact().given('an incident investigator is unauthorized for another case')
      .uponReceiving('read incident impact with an unassigned investigation case')
      .withRequest({
        method: 'GET', path: '/api/v1/context/incidents/incident-1/impact',
        headers: {
          Authorization: 'Bearer pact-operator', Accept: 'application/json',
          'X-Investigation-Case-Id': 'unassigned-case', 'X-Investigation-Purpose': 'INCIDENT_IMPACT',
        },
      })
      .willRespondWith({ status: 403 })
      .executeTest(async server => {
        vi.mocked(contextServiceUrl).mockImplementation(route => `${server.url}${route}`)
        const response = await GET(new NextRequest('http://localhost/api/context/incidents/incident-1/impact?caseId=unassigned-case&purpose=INCIDENT_IMPACT'), {
          params: Promise.resolve({ reference: 'incident-1' }),
        })
        expect(response.status).toBe(403)
        expect(await response.json()).toEqual({ error: 'context_unavailable' })
      })
  })
  it('preserves the effective and known-time authority history contract', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'pact-operator', roles: ['ROLE_COMPLIANCE'] } } as never)
    const id = '44444444-4444-4444-4444-444444444444'
    const instant = '2026-03-01T00:00:00Z'
    const history = {
      root: `delegation:${id}`, effectiveAt: instant, knownAt: instant, observations: [],
      truncated: false, actionAuthorization: 'UNKNOWN',
    }
    await createPact().given('root-scoped authority history has no recorded evidence')
      .uponReceiving('read unknown historical authority for an approved delegation root')
      .withRequest({
        method: 'GET', path: `/api/v1/context/authorizations/${id}`,
        query: { effectiveAt: instant, knownAt: instant },
        headers: { Authorization: 'Bearer pact-operator', Accept: 'application/json',
          'X-Investigation-Case-Id': 'history-case', 'X-Investigation-Purpose': 'AUTHORIZATION_REVIEW' },
      }).willRespondWith({ status: 200, headers: { 'Content-Type': 'application/json' }, body: history })
      .executeTest(async server => {
        vi.mocked(contextServiceUrl).mockImplementation(route => `${server.url}${route}`)
        const response = await getAuthority(new NextRequest(`http://localhost/api/context/authorizations/${id}?caseId=history-case&effectiveAt=${instant}&knownAt=${instant}`), {
          params: Promise.resolve({ id }),
        })
        expect(response.status).toBe(200)
        expect(await response.json()).toEqual(history)
      })
  })

  it('denies historical authority when only another delegation root is assigned', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'pact-operator', roles: ['ROLE_COMPLIANCE'] } } as never)
    const id = '55555555-5555-5555-5555-555555555555'
    await createPact().given('authority history is unauthorized for another root')
      .uponReceiving('read authority history for an unassigned delegation root')
      .withRequest({
        method: 'GET', path: `/api/v1/context/authorizations/${id}`,
        headers: { Authorization: 'Bearer pact-operator', Accept: 'application/json',
          'X-Investigation-Case-Id': 'history-case', 'X-Investigation-Purpose': 'AUTHORIZATION_REVIEW' },
      }).willRespondWith({ status: 403 })
      .executeTest(async server => {
        vi.mocked(contextServiceUrl).mockImplementation(route => `${server.url}${route}`)
        const response = await getAuthority(new NextRequest(`http://localhost/api/context/authorizations/${id}?caseId=history-case`), {
          params: Promise.resolve({ id }),
        })
        expect(response.status).toBe(403)
        expect(await response.json()).toEqual({ error: 'context_unavailable' })
      })
  })

  it('preserves minimized authorized AML source evidence through the real BFF', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'pact-operator', roles: ['ROLE_COMPLIANCE'] } } as never)
    const id = '66666666-6666-4666-8666-666666666666'
    const eventId = '77777777-7777-4777-8777-777777777777'
    const instant = '2026-03-01T00:00:00Z'
    const history = {
      root: `aml-case:${id}`, effectiveAt: instant, knownAt: instant, truncated: false,
      observations: [{
        evidence: {
          eventId, caseId: id, partyId: '88888888-8888-4888-8888-888888888888',
          accountId: null, transactionId: null, eventType: 'aml.case.created.v1',
          status: 'OPEN', previousStatus: null, riskLevel: 'LOW', screeningType: 'MANUAL_INVESTIGATION',
          occurredAt: '2026-02-01T00:00:00Z',
        },
        recordedAt: '2026-02-02T00:00:00Z', evidenceRef: `aml-case:${id}:${eventId}`, contentHash: 'a'.repeat(64),
      }],
    }
    await createPact().given('an assigned AML case has open source evidence')
      .uponReceiving('read minimized AML case evidence under its approved case scope')
      .withRequest({
        method: 'GET', path: `/api/v1/context/aml-cases/${id}`,
        query: { effectiveAt: instant, knownAt: instant },
        headers: { Authorization: 'Bearer pact-operator', Accept: 'application/json',
          'X-Investigation-Case-Id': id, 'X-Investigation-Purpose': 'AML_INVESTIGATION' },
      }).willRespondWith({
        status: 200, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }, body: history,
      })
      .executeTest(async server => {
        vi.mocked(contextServiceUrl).mockImplementation(route => `${server.url}${route}`)
        const response = await getAml(new NextRequest(`http://localhost/api/context/aml-cases/${id}?effectiveAt=${instant}&knownAt=${instant}`), {
          params: Promise.resolve({ id }),
        })
        expect(response.status).toBe(200)
        expect(response.headers.get('Cache-Control')).toBe('no-store')
        expect(await response.json()).toEqual(history)
      })
  })

  it('passes only independently assigned AML network histories through the real BFF', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'pact-operator', roles: ['ROLE_COMPLIANCE'] } } as never)
    const id = '66666666-6666-4666-8666-666666666666'
    const relatedId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    const instant = '2026-03-01T00:00:00Z'
    const evidence = (caseId: string, eventId: string, hash: string) => ({
      root: `aml-case:${caseId}`, effectiveAt: instant, knownAt: instant, truncated: false,
      observations: [{
        evidence: {
          eventId, caseId, partyId: '88888888-8888-4888-8888-888888888888',
          accountId: null, transactionId: null, eventType: 'aml.case.created.v1',
          status: 'OPEN', previousStatus: null, riskLevel: 'LOW', screeningType: 'MANUAL_INVESTIGATION',
          occurredAt: '2026-02-01T00:00:00Z',
        },
        recordedAt: '2026-02-02T00:00:00Z', evidenceRef: `aml-case:${caseId}:${eventId}`, contentHash: hash.repeat(64),
      }],
    })
    const network = {
      root: evidence(id, '77777777-7777-4777-8777-777777777777', 'a'),
      related: [evidence(relatedId, 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'b')],
    }
    await createPact().given('an assigned AML case has open source evidence')
      .uponReceiving('read independently assigned AML cases sharing a source party')
      .withRequest({
        method: 'GET', path: `/api/v1/context/aml-cases/${id}/network`,
        query: { effectiveAt: instant, knownAt: instant },
        headers: { Authorization: 'Bearer pact-operator', Accept: 'application/json',
          'X-Investigation-Case-Id': id, 'X-Investigation-Purpose': 'AML_INVESTIGATION' },
      }).willRespondWith({
        status: 200, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }, body: network,
      })
      .executeTest(async server => {
        vi.mocked(contextServiceUrl).mockImplementation(route => `${server.url}${route}`)
        const response = await getAml(new NextRequest(`http://localhost/api/context/aml-cases/${id}?view=network&effectiveAt=${instant}&knownAt=${instant}`), {
          params: Promise.resolve({ id }),
        })
        expect(response.status).toBe(200)
        expect(response.headers.get('Cache-Control')).toBe('no-store')
        expect(await response.json()).toEqual(network)
      })
  })

})
