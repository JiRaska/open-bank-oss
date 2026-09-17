// SPDX-License-Identifier: Apache-2.0
import path from 'node:path'
import { PactV3, SpecificationVersion } from '@pact-foundation/pact'
import { NextRequest } from 'next/server'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
vi.mock('@/lib/context/server', () => ({ contextServiceUrl: vi.fn() }))
import { auth } from '@/auth'
import { contextServiceUrl } from '@/lib/context/server'
import { GET } from '@/app/api/context/incidents/[reference]/impact/route'

describe('Admin UI incident impact consumer contract', () => {
  afterEach(() => vi.restoreAllMocks())

  it.each([
    { status: 'MISSING', counts: {}, total: 0 },
    { status: 'PARTIAL', counts: { SERVICE: 2 }, total: 2 },
    { status: 'AVAILABLE', counts: { SERVICE: 2 }, total: 2 },
  ])('preserves $status projection semantics through the real BFF', async ({ status, counts, total }) => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'pact-operator', roles: ['ROLE_OPERATOR'] } } as never)
    const pact = new PactV3({
      consumer: 'openbank-admin-ui', provider: 'openbank-context-service',
      dir: path.resolve(process.cwd(), '../pacts'), host: '127.0.0.1',
      spec: SpecificationVersion.SPECIFICATION_VERSION_V3, logLevel: 'error',
    })
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
})
