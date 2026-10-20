// SPDX-License-Identifier: Apache-2.0
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'
const state = vi.hoisted(() => ({ cluster: true, token: 'operator-token', tls: vi.fn() }))
vi.mock('@/lib/treasury-company', async importOriginal => ({ ...await importOriginal<typeof import('@/lib/treasury-company')>(), fetchCompanyTreasury: state.tls }))
vi.mock('@/auth', () => ({ auth: async () => ({ user: { accessToken: state.token, email: 'operator@example.test' } }) }))
vi.mock('@/lib/services/fleet', () => ({ getProxyAllowlist: () => ({
  'treasury-service': { container: 'openbank-treasury-service', port: 8160 },
  'treasury-pension-co': { container: 'openbank-treasury-service', port: 8443 },
}) }))
vi.mock('@/lib/discovery', async () => {
  const company = await import('@/lib/treasury-company')
  // The real snapshot keys the company instance by discoveryServiceKey, so both workloads named
  // treasury-service stay distinct instead of colliding as an ambiguous duplicate.
  const services = [
    { name: 'treasury-service', namespace: 'treasury', port: 8160, scaledToZero: false },
    { name: 'treasury-service', namespace: 'treasury-pension-co', port: 8160, scaledToZero: false },
  ].map(s => ({ ...s, name: company.discoveryServiceKey(s.name, s.namespace) }))
  return {
    inCluster: () => state.cluster,
    resolveInClusterService: async (name: string) => {
      const hit = services.find(s => s.name === name)
      return hit
        ? { state: 'found', service: hit, baseUrl: company.discoveredBaseUrl(hit.name, hit.namespace, hit.port), coverage: 'complete' }
        : { state: 'not_found', coverage: 'complete' }
    },
  }
})
import { GET } from '@/app/api/svc/[service]/[...path]/route'
let fetchMock: ReturnType<typeof vi.fn>
beforeEach(() => { state.tls.mockReset(); state.tls.mockResolvedValue(new Response('{}', {status:202,headers:{etag:'v1'}})); state.cluster = true; state.token = 'operator-token'; fetchMock = vi.fn(async () => new Response('{}')); vi.stubGlobal('fetch', fetchMock) })
afterEach(() => vi.unstubAllGlobals())
async function call(service: string) {
 const req = new NextRequest(`http://localhost/api/svc/${service}/api/v1/treasury/portfolio/period-end?date=2026-12-31`)
 return GET(req, { params: Promise.resolve({ service, path: ['api','v1','treasury','portfolio','period-end'] }) })
}
it('a second legal entity cannot overwrite the bank treasury target', async () => {
 expect((await call('treasury-service')).status).toBe(200)
 expect(fetchMock.mock.calls[0][0]).toBe('http://treasury-service.treasury.svc:8160/api/v1/treasury/portfolio/period-end?date=2026-12-31')
})
it('a company treasury alias never falls back to localhost outside the cluster', async () => {
 state.cluster = false
 expect((await call('treasury-pension-co')).status).toBe(404)
 expect(fetchMock).not.toHaveBeenCalled()
})

it('routes the distinct company identity through mTLS with operator bearer and audit identity', async () => {
 const response = await call('treasury-pension-co')
 expect(response.status).toBe(202); expect(response.headers.get('etag')).toBe('v1')
 expect(fetchMock).not.toHaveBeenCalled()
 const [url, options] = state.tls.mock.calls[0]
 expect(url).toBe('https://treasury-service.treasury-pension-co.svc:8443/api/v1/treasury/portfolio/period-end?date=2026-12-31')
 expect(options.headers.get('authorization')).toBe('Bearer operator-token')
 expect(options.headers.get('x-operator-id')).toBe('admin-ui:operator@example.test')
})
it('retains authentication before touching the company transport', async () => {
 state.token = ''
 expect((await call('treasury-pension-co')).status).toBe(401)
 expect(state.tls).not.toHaveBeenCalled(); expect(fetchMock).not.toHaveBeenCalled()
})

it('preserves a company upstream bodyless status through NextResponse', async () => {
 state.tls.mockResolvedValue(new Response(null, {status:204}))
 const response = await call('treasury-pension-co')
 expect(response.status).toBe(204); expect(await response.text()).toBe('')
})
