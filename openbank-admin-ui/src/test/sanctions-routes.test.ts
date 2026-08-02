// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// These routes must never invent data: whatever sanctions-service answers is what the
// operator sees, and a backend failure stays a failure rather than degrading into a
// synthetic "clear" result. That is what this file has always defended.
//
// They are now session-gated and relay the operator's bearer (see
// sanctions-security-bff-auth.test.ts for why), so each case supplies a session. The
// upstream *status* still passes through; the upstream error *body* is deliberately
// replaced by a generic envelope (ADR-0080 P1 — upstream detail stays in the server log,
// never in the browser), which is the same contract the closings BFF helper documents.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }

describe('sanctions api routes', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('checks route forwards the backend failure status instead of an empty fallback', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'backend down' }), {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
    })))

    const { GET } = await import('../app/api/sanctions/checks/route')
    const response = await GET()

    expect(response.status).toBe(503)
    // Generic envelope, not the upstream text — and emphatically not an empty list.
    await expect(response.json()).resolves.toEqual({ error: 'upstream_error' })
  })

  it('screen route returns backend payload and never local fallback mock', async () => {
    const payload = { id: 'check-1', status: 'CLEAR', name: 'Alice Example' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(payload), {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
    })))

    const { POST } = await import('../app/api/sanctions/screen/route')
    const response = await POST(new Request('http://localhost/api/sanctions/screen', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Alice Example', entityType: 'INDIVIDUAL', idempotencyKey: 'manual-1' }),
    }) as never)

    expect(response.status).toBe(201)
    await expect(response.json()).resolves.toEqual(payload)
  })

  it('lists route returns backend list payload without synthetic defaults', async () => {
    const payload = [{ id: '1', listType: 'OFAC_SDN', displayName: 'OFAC', enabled: true }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(payload), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })))

    const { GET } = await import('../app/api/sanctions/lists/route')
    const response = await GET()

    expect(response.status).toBe(200)
    await expect(response.json()).resolves.toEqual(payload)
  })
})
