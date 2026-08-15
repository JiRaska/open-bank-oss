// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Bearer-relay + envelope tests for the swarm-case BFF routes (ADR-0246). The page
// renders three honest empty states off this envelope, so the mapping is pinned here:
// upstream 404 -> not_deployed (list), thrown fetch -> unreachable, upstream 404 on
// detail -> honest "no such case". Auth assertions follow sanctions-security-bff-auth:
// a session-less call must never touch the backend, and the outgoing request must
// carry the operator's bearer.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }

function listReq(query = ''): NextRequest {
  return new NextRequest(`http://localhost/api/iaops/cases${query}`)
}

function detailCtx(caseId: string) {
  return { params: Promise.resolve({ caseId }) }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function lastFetchCall(): [string, RequestInit] {
  const mock = vi.mocked(global.fetch)
  return mock.mock.calls[mock.mock.calls.length - 1] as [string, RequestInit]
}

beforeEach(() => {
  vi.resetModules()
  vi.mocked(auth).mockResolvedValue(SESSION as never)
})
afterEach(() => { vi.restoreAllMocks() })

describe('GET /api/iaops/cases', () => {
  it('returns 401 without a session and never calls the backend', async () => {
    vi.mocked(auth).mockResolvedValue(null)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/iaops/cases/route')
    const res = await GET(listReq())
    expect(res.status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('relays the operator bearer and forwards status + limit params', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ cases: [] })))
    const { GET } = await import('@/app/api/iaops/cases/route')
    const res = await GET(listReq('?status=OPEN&limit=10'))
    expect(res.status).toBe(200)
    const [url, init] = lastFetchCall()
    expect(url).toContain('/api/v1/case-coordinator/cases?')
    expect(url).toContain('status=OPEN')
    expect(url).toContain('limit=10')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
  })

  it('caps an excessive limit instead of forwarding it unbounded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ cases: [] })))
    const { GET } = await import('@/app/api/iaops/cases/route')
    await GET(listReq('?limit=99999'))
    const [url] = lastFetchCall()
    expect(url).toContain('limit=200')
  })

  it('rejects an unknown status filter without calling the backend', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/iaops/cases/route')
    const res = await GET(listReq('?status=WHATEVER'))
    expect(res.status).toBe(400)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('maps an upstream 404 to the not_deployed empty state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, 404)))
    const { GET } = await import('@/app/api/iaops/cases/route')
    const body = await (await GET(listReq())).json()
    expect(body).toEqual({ available: false, reason: 'not_deployed', cases: [] })
  })

  it('maps a thrown fetch (DNS/timeout, pod absent) to unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('getaddrinfo ENOTFOUND')))
    const { GET } = await import('@/app/api/iaops/cases/route')
    const body = await (await GET(listReq())).json()
    expect(body).toEqual({ available: false, reason: 'unreachable', cases: [] })
  })

  it('passes the upstream case list through on success', async () => {
    const cases = [{ caseId: 'c-1', status: 'OPEN', contributionCount: 3 }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ cases })))
    const { GET } = await import('@/app/api/iaops/cases/route')
    const body = await (await GET(listReq())).json()
    expect(body).toEqual({ available: true, cases })
  })
})

describe('GET /api/iaops/cases/[caseId]', () => {
  it('returns 401 without a session and never calls the backend', async () => {
    vi.mocked(auth).mockResolvedValue(null)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/iaops/cases/[caseId]/route')
    const res = await GET(listReq(), detailCtx('c-1'))
    expect(res.status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects an unsafe case id without calling the backend', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/iaops/cases/[caseId]/route')
    const res = await GET(listReq(), detailCtx('../admin'))
    expect(res.status).toBe(400)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('relays the operator bearer to the detail endpoint', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ caseId: 'c-1', entries: [] })))
    const { GET } = await import('@/app/api/iaops/cases/[caseId]/route')
    await GET(listReq(), detailCtx('c-1'))
    const [url, init] = lastFetchCall()
    expect(url).toContain('/api/v1/case-coordinator/cases/c-1')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
  })

  it('forwards an upstream 404 as an honest not_found, not an availability failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, 404)))
    const { GET } = await import('@/app/api/iaops/cases/[caseId]/route')
    const res = await GET(listReq(), detailCtx('no-such-case'))
    expect(res.status).toBe(404)
    expect((await res.json()).error).toBe('not_found')
  })

  it('maps a thrown fetch to unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('connect ECONNREFUSED')))
    const { GET } = await import('@/app/api/iaops/cases/[caseId]/route')
    const body = await (await GET(listReq(), detailCtx('c-1'))).json()
    expect(body).toEqual({ available: false, reason: 'unreachable' })
  })

  it('passes the thread through on success', async () => {
    const thread = { caseId: 'c-1', status: 'CONTESTED', entries: [{ type: 'CASE_OPENED', atEpochMs: 1 }] }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(thread)))
    const { GET } = await import('@/app/api/iaops/cases/[caseId]/route')
    const body = await (await GET(listReq(), detailCtx('c-1'))).json()
    expect(body).toEqual({ available: true, thread })
  })
})
