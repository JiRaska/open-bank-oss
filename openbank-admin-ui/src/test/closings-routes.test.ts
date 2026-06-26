// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Integration tests for the closings BFF (/api/closings/**, ADR-0069 D3)
// (ADR-0076 Layer 1 — BFF route integration tests, mocked upstream + session).

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Mock the NextAuth session — the routes are session-gated and relay the
// operator's bearer to statement-service.
vi.mock('@/auth', () => ({
  auth: vi.fn(),
}))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }

function lastFetchCall() {
  const mock = vi.mocked(global.fetch)
  return mock.mock.calls[mock.mock.calls.length - 1] as [string, RequestInit]
}

describe('closings api routes', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('GET /api/closings/runs forwards the upstream list with the operator bearer', async () => {
    const payload = [{
      id: 'd2b7e9a0-0000-4000-8000-000000000001', trigger: 'SCHEDULED', status: 'COMPLETED',
      periodFrom: '2026-05-01', periodTo: '2026-05-31', accountsEnumerated: 12,
      pocketsClosed: 14, pocketsFailed: 0, pocketsSkipped: 2,
      startedAt: '2026-06-01T00:30:00Z', finishedAt: '2026-06-01T00:30:09Z',
    }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(payload), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })))

    const { GET } = await import('@/app/api/closings/runs/route')
    const res = await GET(new NextRequest('http://localhost/api/closings/runs?limit=5'))

    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toEqual(payload)
    const [url, init] = lastFetchCall()
    expect(String(url)).toContain('/api/v1/statements/close-runs?limit=5')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
  })

  it('GET /api/closings/runs clamps a hostile limit instead of relaying it', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('[]', {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })))

    const { GET } = await import('@/app/api/closings/runs/route')
    await GET(new NextRequest('http://localhost/api/closings/runs?limit=99999'))

    const [url] = lastFetchCall()
    expect(String(url)).toContain('limit=100')
  })

  it('GET /api/closings/runs/latest passes the 204 never-ran signal through', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))

    const { GET } = await import('@/app/api/closings/runs/latest/route')
    const res = await GET()

    expect(res.status).toBe(204)
    await expect(res.text()).resolves.toBe('')
  })

  it('upstream 5xx degrades to a generic error body (no upstream detail leaks)', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('stacktrace: secret internals', {
      status: 500, headers: { 'Content-Type': 'text/plain' },
    })))

    const { GET } = await import('@/app/api/closings/runs/latest/route')
    const res = await GET()

    expect(res.status).toBe(500)
    await expect(res.json()).resolves.toEqual({ error: 'upstream_error' })
  })

  it('unreachable upstream maps to 502 upstream_unreachable (classifyBffFailure contract)', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')))

    const { GET } = await import('@/app/api/closings/runs/route')
    const res = await GET(new NextRequest('http://localhost/api/closings/runs'))

    expect(res.status).toBe(502)
    await expect(res.json()).resolves.toEqual({ error: 'upstream_unreachable' })
  })

  it('rejects with 401 when there is no operator session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const { GET } = await import('@/app/api/closings/runs/route')
    const res = await GET(new NextRequest('http://localhost/api/closings/runs'))

    expect(res.status).toBe(401)
    await expect(res.json()).resolves.toEqual({ error: 'unauthorized' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('POST /api/closings/runs triggers the manual catch-up and returns the accepted run', async () => {
    const run = {
      id: 'd2b7e9a0-0000-4000-8000-000000000002', trigger: 'MANUAL', status: 'COMPLETED',
      periodFrom: '2026-05-01', periodTo: '2026-05-31', accountsEnumerated: 12,
      pocketsClosed: 1, pocketsFailed: 0, pocketsSkipped: 13,
      startedAt: '2026-06-12T10:00:00Z', finishedAt: '2026-06-12T10:00:03Z',
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(run), {
      status: 202, headers: { 'Content-Type': 'application/json' },
    })))

    const { POST } = await import('@/app/api/closings/runs/route')
    const res = await POST()

    expect(res.status).toBe(202)
    await expect(res.json()).resolves.toEqual(run)
    const [url, init] = lastFetchCall()
    expect(String(url)).toContain('/api/v1/statements/close-runs')
    expect(init.method).toBe('POST')
  })

  it('POST /api/closings/runs is forbidden for a signed-in viewer (closings:run gate)', async () => {
    vi.mocked(auth).mockResolvedValue(
      { user: { accessToken: 'viewer-token', roles: ['ROLE_VIEWER'] } } as never,
    )
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const { POST } = await import('@/app/api/closings/runs/route')
    const res = await POST()

    expect(res.status).toBe(403)
    await expect(res.json()).resolves.toEqual({ error: 'forbidden' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('GET failures validates the runId before relaying upstream', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const { GET } = await import('@/app/api/closings/runs/[runId]/failures/route')
    const res = await GET(
      new NextRequest('http://localhost/api/closings/runs/not-a-uuid/failures'),
      { params: Promise.resolve({ runId: 'not-a-uuid' }) },
    )

    expect(res.status).toBe(400)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('GET failures forwards the per-pocket failure list for a valid runId', async () => {
    const runId = 'd2b7e9a0-0000-4000-8000-000000000001'
    const payload = [{
      id: 'f0000000-0000-4000-8000-000000000001', runId,
      accountId: 'a0000000-0000-4000-8000-000000000001', pocketCurrency: 'EUR',
      periodFrom: '2026-05-01', periodTo: '2026-05-31',
      reason: 'UPSTREAM', detail: 'balance-service read timed out', failedAt: '2026-06-01T00:30:05Z',
    }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(payload), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })))

    const { GET } = await import('@/app/api/closings/runs/[runId]/failures/route')
    const res = await GET(
      new NextRequest(`http://localhost/api/closings/runs/${runId}/failures`),
      { params: Promise.resolve({ runId }) },
    )

    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toEqual(payload)
    const [url] = lastFetchCall()
    expect(String(url)).toContain(`/api/v1/statements/close-runs/${runId}/failures`)
  })
})
