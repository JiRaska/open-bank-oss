// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'

vi.mock('@/auth', () => ({ auth: (handler: unknown) => handler }))

describe('trailing slash handling around the Envoy tool gate', () => {
  it('keeps the ext_authz URI intact for every gated tool', async () => {
    const { default: proxy } = await import('@/proxy')
    const middleware = proxy as unknown as (request: NextRequest) => Response

    for (const tool of ['grafana', 'alertmanager', 'pyrra']) {
      const request = new NextRequest(`https://admin.open-bank.tech/api/gate/${tool}/tools/${tool}/`)
      const response = middleware(request)
      expect(response.status, tool).toBe(200)
      expect(response.headers.get('location'), tool).toBeNull()
      expect(response.headers.get('x-middleware-next'), tool).toBe('1')
    }
  })

  it('preserves the former slashless canonical redirect and query on console routes', async () => {
    const { default: proxy } = await import('@/proxy')
    const middleware = proxy as unknown as (request: NextRequest) => Response
    const response = middleware(new NextRequest('https://admin.open-bank.tech/system/health/?tab=services'))

    expect(response.status).toBe(308)
    expect(response.headers.get('location')).toBe('https://admin.open-bank.tech/system/health?tab=services')
  })

  it('keeps Next canonical redirects for trailing-slash requests on formerly excluded public paths', async () => {
    const { default: proxy } = await import('@/proxy')
    const middleware = proxy as unknown as (request: NextRequest) => Response

    for (const path of ['/api/auth/session/', '/api/security/kpis/metrics/', '/brand/logo/']) {
      const response = middleware(new NextRequest(`https://admin.open-bank.tech${path}?source=login`))
      expect(response.status, path).toBe(308)
      expect(response.headers.get('location'), path).toBe(
        `https://admin.open-bank.tech${path.replace(/\/+$/, '')}?source=login`,
      )
    }
  })

  it('opts out of Next normalization so the proxy can apply the route-specific policy', () => {
    const config = readFileSync(path.resolve(process.cwd(), 'next.config.mjs'), 'utf8')
    expect(config).toMatch(/skipTrailingSlashRedirect:\s*true/)
  })
})
