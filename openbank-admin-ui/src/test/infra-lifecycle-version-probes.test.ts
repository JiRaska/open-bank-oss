// SPDX-License-Identifier: Apache-2.0

import { afterEach, expect, it, vi } from 'vitest'

vi.mock('@/lib/discovery', () => ({ inCluster: () => true }))

afterEach(() => { vi.unstubAllGlobals() })

it('reads Pyroscope and Alloy running versions from verified sandbox build-info surfaces', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('pyroscope.observability.svc')) return Response.json({ status: 'success', data: { version: '1.21.0' } })
    if (url.includes('alloy.observability.svc')) return new Response('alloy_build_info{branch="HEAD",version="v1.20.0"} 1\n')
    return new Response('', { status: 503 })
  }))

  const { GET } = await import('@/app/api/infra/lifecycle/route')
  const response = await GET()
  expect(response.status).toBe(200)
  const body = await response.json() as { components: { id: string; running: { version: string | null; source: string } }[] }
  expect(body.components.find(component => component.id === 'pyroscope')?.running).toEqual({ version: '1.21.0', source: 'build-info-probe' })
  expect(body.components.find(component => component.id === 'alloy')?.running).toEqual({ version: '1.20.0', source: 'build-info-probe' })
  expect(body.components.find(component => component.id === 'envoy-gateway')?.running).toEqual({ version: '1.9.2', source: 'gitops-chart-version' })
  expect(body.components.find(component => component.id === 'litellm')?.running).toEqual({ version: '1.104.0', source: 'gitops-image-tag' })
  expect(body.components.find(component => component.id === 'langfuse-web')?.running).toEqual({ version: '4.50.0', source: 'gitops-image-tag' })
  expect(body.components.find(component => component.id === 'presidio-analyzer')?.running).toEqual({ version: '2.2.362', source: 'gitops-image-tag' })
})
