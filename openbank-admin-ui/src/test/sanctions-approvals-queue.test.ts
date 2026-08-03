// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Issue #3472: sanctions-service served no pending-approvals list, so #3465's checker UI was an
// id field with "paste what someone hands you". These cover the BFF read half.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }

function lastFetchCall() {
  const mock = vi.mocked(global.fetch)
  return mock.mock.calls[mock.mock.calls.length - 1] as [string, RequestInit]
}

function respond(body: unknown, status = 200) {
  return vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
  )
}

describe('sanctions pending-approvals queue BFF', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('reads the queue with the operator bearer', async () => {
    global.fetch = respond([{ id: 'approval-1', action: 'sanctions.clear', status: 'PENDING', makerId: 'operator-1' }])
    const { GET } = await import('../app/api/sanctions/approvals/route')

    const res = await GET(new NextRequest('http://localhost/api/sanctions/approvals'))

    expect(res.status).toBe(200)
    const [url, init] = lastFetchCall()
    expect(url).toBe('http://openbank-sanctions-service:8123/api/v1/sanctions/approvals')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer operator-token')
  })

  it('passes an explicit limit through to the upstream', async () => {
    global.fetch = respond([])
    const { GET } = await import('../app/api/sanctions/approvals/route')

    await GET(new NextRequest('http://localhost/api/sanctions/approvals?limit=5'))

    // The BFF must not silently swallow the parameter, or a supervisor's "show me more" does
    // nothing.
    expect(lastFetchCall()[0]).toBe('http://openbank-sanctions-service:8123/api/v1/sanctions/approvals?limit=5')
  })

  it('refuses to build a path out of a non-numeric limit', async () => {
    // CodeQL flagged the first draft: `path` is interpolated into the helper's console.error, so a
    // raw query value reached a log format string (js/tainted-format-string, high) and could inject
    // newlines into the log stream (js/log-injection). A parsed integer cannot carry either — and
    // `?limit=abc` was never a legitimate request anyway.
    global.fetch = respond([])
    const { GET } = await import('../app/api/sanctions/approvals/route')

    await GET(new NextRequest('http://localhost/api/sanctions/approvals?limit=abc%0AInjected'))

    expect(lastFetchCall()[0]).toBe('http://openbank-sanctions-service:8123/api/v1/sanctions/approvals')
  })

  it('clamps an out-of-range limit instead of forwarding it', async () => {
    global.fetch = respond([])
    const { GET } = await import('../app/api/sanctions/approvals/route')

    await GET(new NextRequest('http://localhost/api/sanctions/approvals?limit=100000'))

    expect(lastFetchCall()[0]).toBe('http://openbank-sanctions-service:8123/api/v1/sanctions/approvals?limit=200')
  })

  it('refuses without a session before touching the backend', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    global.fetch = respond([])
    const { GET } = await import('../app/api/sanctions/approvals/route')

    const res = await GET(new NextRequest('http://localhost/api/sanctions/approvals'))

    expect(res.status).toBe(401)
    expect(global.fetch).not.toHaveBeenCalled()
  })

  it('surfaces an upstream failure as a non-200 so the UI never renders it as an empty queue', async () => {
    // The dangerous rendering is "No approvals waiting" for a read that failed. The route must
    // hand the client something it can distinguish; the page keys its warning off !res.ok.
    global.fetch = respond({ error: 'boom' }, 503)
    const { GET } = await import('../app/api/sanctions/approvals/route')

    const res = await GET(new NextRequest('http://localhost/api/sanctions/approvals'))

    expect(res.status).toBe(503)
    await expect(res.json()).resolves.toEqual({ error: 'upstream_error' })
  })
})
