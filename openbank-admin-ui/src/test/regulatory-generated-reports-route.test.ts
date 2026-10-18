// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// The report-generation BFF must relay the operator's bearer to every source (admin-ui CLAUDE.md
// rule #7), refuse before touching a backend without a session or the regulatory permission, and
// keep upstream detail out of the browser.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
vi.mock('@/lib/discovery', () => ({
  inCluster: () => true,
  resolveInClusterBaseUrl: vi.fn(async (name: string) => `http://${name}.test:8080`),
}))
vi.mock('@/lib/services/fleet', () => ({
  getProxyAllowlist: () => ({ 'transaction-service': { container: 'tx', port: 8080 } }),
}))

import { auth } from '@/auth'

const ctx = (reportId: string) => ({ params: Promise.resolve({ reportId }) })

describe('GET /api/regulatory/returns/[reportId]', () => {
  beforeEach(() => { vi.resetModules() })
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it('generates the report with the operator bearer on every upstream call', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_COMPLIANCE'] } } as never)
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ data: [] }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const { GET } = await import('@/app/api/regulatory/returns/[reportId]/route')
    const res = await GET(new Request('http://ui/api/regulatory/returns/sdat-payments?period=2026-09'), ctx('sdat-payments'))

    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body).toMatchObject({ reportId: 'sdat-payments', period: '2026-09', closed: true })
    expect(fetchMock).toHaveBeenCalled()
    for (const [url, init] of fetchMock.mock.calls as unknown as [string, RequestInit][]) {
      expect(url).toContain('http://transaction-service.test:8080/api/v1/transactions/search')
      expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
    }
  })

  it('answers 401 without a session and never calls a backend', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/regulatory/returns/[reportId]/route')
    const res = await GET(new Request('http://ui/api/regulatory/returns/sdat-payments'), ctx('sdat-payments'))
    expect(res.status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('answers 403 for a role without regulatory access', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't', roles: ['ROLE_VIEWER'] } } as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/regulatory/returns/[reportId]/route')
    const res = await GET(new Request('http://ui/api/regulatory/returns/sdat-payments'), ctx('sdat-payments'))
    expect(res.status).toBe(403)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('maps an undeployed source to the proxy not_deployed shape without leaking upstream bodies', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't', roles: ['ROLE_ADMIN'] } } as never)
    vi.stubGlobal('fetch', vi.fn())
    const { GET } = await import('@/app/api/regulatory/returns/[reportId]/route')
    // dora-ict reads security-scanner-service, which this test's catalog allowlist omits.
    const res = await GET(new Request('http://ui/api/regulatory/returns/dora-ict?period=2026-09'), ctx('dora-ict'))
    expect(res.status).toBe(404)
    expect((await res.json()).error).toMatch(/^Unknown service/)
  })

  it('rejects an invalid period', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't', roles: ['ROLE_ADMIN'] } } as never)
    const { GET } = await import('@/app/api/regulatory/returns/[reportId]/route')
    const res = await GET(new Request('http://ui/api/regulatory/returns/fatca-report?period=2026-09'), ctx('fatca-report'))
    expect(res.status).toBe(400)
  })
})
