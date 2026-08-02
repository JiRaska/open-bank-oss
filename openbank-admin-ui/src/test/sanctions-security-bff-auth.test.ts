// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Regression tests: the sanctions and security BFF routes must relay the operator's bearer.
//
// Both surfaces shipped without an Authorization header against OIDC-gated backends, so a
// signed-in operator got 401s from healthy services — rendered as "session expired" on the
// sanctions screen, "Invalid JSON" on its list tab and "scanner HTTP 401" on the security
// screen. Each assertion below fails against that code.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
// The security route resolves the scanner through k8s discovery; pin it so the test never
// touches the API server.
vi.mock('@/lib/discovery', () => ({ resolveInClusterBaseUrl: vi.fn().mockResolvedValue('http://scanner:8120') }))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }

function lastFetchCall() {
  const mock = vi.mocked(global.fetch)
  return mock.mock.calls[mock.mock.calls.length - 1] as [string, RequestInit]
}

function jsonOk(body: unknown) {
  return vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }),
  )
}

describe('sanctions BFF relays the operator bearer', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('GET /api/sanctions/lists sends Authorization and returns the upstream body', async () => {
    const lists = [{ id: 'l1', listType: 'OFAC_SDN', enabled: true }]
    vi.stubGlobal('fetch', jsonOk(lists))

    const { GET } = await import('@/app/api/sanctions/lists/route')
    const res = await GET()

    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toEqual(lists)
    const [url, init] = lastFetchCall()
    expect(String(url)).toContain('/api/v1/sanctions/lists')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
  })

  it('GET /api/sanctions/checks sends Authorization', async () => {
    vi.stubGlobal('fetch', jsonOk([]))

    const { GET } = await import('@/app/api/sanctions/checks/route')
    await GET()

    const [url, init] = lastFetchCall()
    expect(String(url)).toContain('/api/v1/sanctions')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
  })

  it('POST /api/sanctions/screen sends Authorization and forwards the body', async () => {
    vi.stubGlobal('fetch', jsonOk({ status: 'CLEAR' }))

    const { POST } = await import('@/app/api/sanctions/screen/route')
    await POST(new NextRequest('http://localhost/api/sanctions/screen', {
      method: 'POST',
      body: JSON.stringify({ name: 'Jan Novak' }),
      headers: { 'Content-Type': 'application/json' },
    }))

    const [url, init] = lastFetchCall()
    expect(String(url)).toContain('/api/v1/sanctions/screen')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
    expect(JSON.parse(String(init.body))).toEqual({ name: 'Jan Novak' })
  })

  it('refuses without a session instead of calling the backend anonymously', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = jsonOk([])
    vi.stubGlobal('fetch', fetchMock)

    const { GET } = await import('@/app/api/sanctions/lists/route')
    const res = await GET()

    expect(res.status).toBe(401)
    await expect(res.json()).resolves.toEqual({ error: 'unauthorized' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('does not leak an upstream error body to the browser', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response('database "openbank_sanctions" is on fire', { status: 500 }),
    ))

    const { GET } = await import('@/app/api/sanctions/lists/route')
    const res = await GET()

    expect(res.status).toBe(500)
    await expect(res.json()).resolves.toEqual({ error: 'upstream_error' })
  })
})

describe('security BFF relays the operator bearer', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
    // Force the live-scanner path: with a bundled report present the route never calls out.
    process.env.OPENBANK_SECURITY_REPORT = '/nonexistent/security-report.json'
  })
  afterEach(() => {
    vi.restoreAllMocks()
    delete process.env.OPENBANK_SECURITY_REPORT
  })

  it('GET /api/security sends Authorization to the scanner', async () => {
    vi.stubGlobal('fetch', jsonOk({ services: [], averageScore: 91 }))

    const { GET } = await import('@/app/api/security/route')
    const res = await GET()

    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toMatchObject({ available: true })
    const [url, init] = lastFetchCall()
    expect(String(url)).toContain('/api/v1/security/report')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
  })

  it('reports a rejected token as unauthorized, not as a raw scanner status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })))

    const { GET } = await import('@/app/api/security/route')
    const body = await (await GET()).json()

    expect(body).toEqual({ available: false, reason: 'unauthorized' })
  })
})
