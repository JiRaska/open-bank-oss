// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// The report-generation BFF must relay the operator's bearer to every source (admin-ui CLAUDE.md
// rule #7), refuse before touching a backend without a session or the regulatory permission, and
// keep upstream detail out of the browser.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
vi.mock('@/lib/discovery', () => ({
  inCluster: () => true,
  resolveInClusterService: vi.fn(async (name: string) => ({ state: 'found', service: {}, baseUrl: `http://${name}.test:8080`, coverage: 'partial' })),
}))
vi.mock('@/lib/services/fleet', () => ({
  getProxyAllowlist: () => ({ 'transaction-service': { container: 'tx', port: 8080 } }),
}))

import { auth } from '@/auth'
import { resolveInClusterService } from '@/lib/discovery'

const ctx = (reportId: string) => ({ params: Promise.resolve({ reportId }) })

describe('GET /api/regulatory/returns/[reportId]', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(resolveInClusterService).mockImplementation(async (name: string) => ({ state: 'found', service: {} as never, baseUrl: `http://${name}.test:8080`, coverage: 'partial' }))
  })
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

  it('does not label a source undeployed when Kubernetes discovery is incomplete', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't', roles: ['ROLE_ADMIN'] } } as never)
    vi.mocked(resolveInClusterService).mockResolvedValue({ state: 'unknown', coverage: 'partial' })
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/regulatory/returns/[reportId]/route')

    const res = await GET(new Request('http://ui/api/regulatory/returns/sdat-payments?period=2026-09'), ctx('sdat-payments'))

    expect(res.status).toBe(503)
    expect(await res.json()).toEqual({ error: 'discovery_unavailable' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects an invalid period', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't', roles: ['ROLE_ADMIN'] } } as never)
    const { GET } = await import('@/app/api/regulatory/returns/[reportId]/route')
    const res = await GET(new Request('http://ui/api/regulatory/returns/fatca-report?period=2026-09'), ctx('fatca-report'))
    expect(res.status).toBe(400)
  })
})

describe('regulatory report failure log privacy', () => {
  const detail = 'synthetic-customer@example.test\r\nFORGED %s %o transaction=synthetic-123'
  const failures = [
    { kind: 'not_deployed', status: 404, body: { error: 'Unknown service: transaction-service', service: 'transaction-service' } },
    { kind: 'discovery_unavailable', status: 503, body: { error: 'discovery_unavailable' } },
    { kind: 'unauthorized', status: 403, body: { error: 'unauthorized', service: 'transaction-service' } },
    { kind: 'unreachable', status: 502, body: { error: 'upstream_unreachable', service: 'transaction-service' } },
    { kind: 'result_limit', status: 500, body: { error: 'result_limit', service: 'transaction-service' } },
    { kind: 'error', status: 500, body: { error: 'upstream_error', service: 'transaction-service' } },
  ] as const

  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_COMPLIANCE'] } } as never)
  })
  afterEach(() => {
    vi.doUnmock('@/lib/regulatory/generatedReports')
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it.each(failures)('preserves $kind response without logging source detail', async ({ kind, status, body }) => {
    vi.doMock('@/lib/regulatory/generatedReports', async importOriginal => {
      const actual = await importOriginal<typeof import('@/lib/regulatory/generatedReports')>()
      return {
        ...actual,
        generateReport: vi.fn().mockRejectedValue(new actual.ReportSourceError(kind, 'transaction-service', detail)),
      }
    })
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { GET } = await import('@/app/api/regulatory/returns/[reportId]/route')
    const { generateReport } = await import('@/lib/regulatory/generatedReports')

    const res = await GET(new Request('http://ui/api/regulatory/returns/sdat-payments?period=2026-09'), ctx('sdat-payments'))

    expect(res.status).toBe(status)
    expect(await res.json()).toEqual(body)
    expect(generateReport).toHaveBeenCalledWith('sdat-payments', '2026-09', expect.objectContaining({
      get: expect.any(Function), request: expect.any(Function),
    }), expect.any(Object))
    expect(log).toHaveBeenCalledExactlyOnceWith('regulatory BFF: report source failed')
  })

  it('preserves generic 500 without logging the exception or request values', async () => {
    vi.doMock('@/lib/regulatory/generatedReports', async importOriginal => {
      const actual = await importOriginal<typeof import('@/lib/regulatory/generatedReports')>()
      return { ...actual, generateReport: vi.fn().mockRejectedValue(new Error(detail, { cause: detail })) }
    })
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { GET } = await import('@/app/api/regulatory/returns/[reportId]/route')

    const res = await GET(new Request('http://ui/api/regulatory/returns/sdat-payments?period=2026-09'), ctx('sdat-payments'))

    expect(res.status).toBe(500)
    expect(await res.json()).toEqual({ error: 'upstream_error' })
    expect(log).toHaveBeenCalledExactlyOnceWith('regulatory BFF: report generation failed')
  })

  it('restores real generation and bearer relay after the isolated error mocks', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ data: [] }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const { generateReport } = await import('@/lib/regulatory/generatedReports')
    expect(vi.isMockFunction(generateReport)).toBe(false)
    const { GET } = await import('@/app/api/regulatory/returns/[reportId]/route')

    const res = await GET(new Request('http://ui/api/regulatory/returns/sdat-payments?period=2026-09'), ctx('sdat-payments'))

    expect(res.status).toBe(200)
    expect(await res.json()).toMatchObject({ reportId: 'sdat-payments', period: '2026-09' })
    expect(fetchMock).toHaveBeenCalled()
    for (const [, init] of fetchMock.mock.calls as unknown as [string, RequestInit][]) {
      expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
    }
  })
})
