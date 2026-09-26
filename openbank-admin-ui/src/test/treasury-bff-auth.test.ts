// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0315 / #10618: the Treasury section reaches treasury-service ONLY through the /api/svc BFF,
// which must relay the signed-in person's OWN bearer (treasury records that principal as dealer /
// approver and compares two of them for four-eyes), refuse a session-less call before touching the
// backend, and pass the backend's RBAC / four-eyes refusals through unchanged.
import { NextRequest } from 'next/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const auth = vi.fn()
vi.mock('@/auth', () => ({ auth }))
vi.mock('@/lib/discovery', () => ({ inCluster: () => false, discoverServices: vi.fn() }))

const ctx = (path: string[]) => ({ params: Promise.resolve({ service: 'treasury-service', path }) })
const DEALS = ['api', 'v1', 'treasury', 'deals']

async function route() {
  return import('@/app/api/svc/[service]/[...path]/route')
}

describe('treasury BFF — bearer relay through /api/svc/treasury-service', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
    vi.unstubAllGlobals()
    delete process.env.SERVICES_HOST
  })

  it('refuses a session-less call with 401 and never contacts treasury-service', async () => {
    auth.mockResolvedValue(null)
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const { GET } = await route()
    const res = await GET(new NextRequest('http://localhost/api/svc/treasury-service/api/v1/treasury/deals'), ctx(DEALS))
    expect(res.status).toBe(401)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('forwards the operator’s own access token to the treasury-service port (8160)', async () => {
    auth.mockResolvedValue({ user: { accessToken: 'dealer-token', email: 'dealer@example.test' } })
    const fetch = vi.fn().mockResolvedValue(new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetch)
    const { GET } = await route()
    const res = await GET(new NextRequest('http://localhost/api/svc/treasury-service/api/v1/treasury/deals?state=DRAFT'), ctx(DEALS))
    expect(res.status).toBe(200)
    expect(fetch).toHaveBeenCalledOnce()
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8160/api/v1/treasury/deals?state=DRAFT')
    expect(new Headers(init.headers).get('authorization')).toBe('Bearer dealer-token')
  })

  it('relays a POST body and its Idempotency-Key, and passes a backend RBAC denial (403) through, not a 200', async () => {
    auth.mockResolvedValue({ user: { accessToken: 'approver-token' } })
    const fetch = vi.fn().mockResolvedValue(new Response('', { status: 403 }))
    vi.stubGlobal('fetch', fetch)
    const { POST } = await route()
    const req = new NextRequest('http://localhost/api/svc/treasury-service/api/v1/treasury/deals', {
      method: 'POST', body: JSON.stringify({ product: 'MM_PLACEMENT' }),
      headers: { 'content-type': 'application/json', 'idempotency-key': 'intent-7f3a' },
    })
    const res = await POST(req, ctx(DEALS))
    expect(res.status).toBe(403)
    const init = fetch.mock.calls[0][1] as RequestInit
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('authorization')).toBe('Bearer approver-token')
    expect(new TextDecoder().decode(init.body as ArrayBuffer)).toContain('MM_PLACEMENT')
    // treasury requires Idempotency-Key on every POST; the BFF must forward it unchanged
    expect(new Headers(init.headers).get('idempotency-key')).toBe('intent-7f3a')
  })

  it('passes the service’s 422 FOUR_EYES_VIOLATION envelope through verbatim for the page to render', async () => {
    auth.mockResolvedValue({ user: { accessToken: 'approver-token' } })
    const body = JSON.stringify({ error: 'FOUR_EYES_VIOLATION', message: 'approver must differ from creator' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 422, headers: { 'content-type': 'application/json' } })))
    const { POST } = await route()
    const res = await POST(new NextRequest('http://localhost/api/svc/treasury-service/api/v1/treasury/deals/d-1/approve', { method: 'POST' }), ctx([...DEALS, 'd-1', 'approve']))
    expect(res.status).toBe(422)
    expect(await res.json()).toEqual({ error: 'FOUR_EYES_VIOLATION', message: 'approver must differ from creator' })
  })
})
