// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Issue #3334: POST /api/v1/sanctions/review had no caller in the product, so a HIT could never
// be dispositioned. These cover the BFF half — the review route, the four-eyes retry header, the
// checker's decide route, and the two list routes that #3336 left hand-rolled without a bearer.
//
// The load-bearing case is "X-Approval-Id reaches the upstream". Without it the four-eyes retry
// silently loops: AuthorizeInterceptor reads the id off the REQUEST, so a dropped header makes it
// mint a fresh PendingApproval and answer 202 again — every individual call looking healthy.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }

function lastFetchCall() {
  const mock = vi.mocked(global.fetch)
  return mock.mock.calls[mock.mock.calls.length - 1] as [string, RequestInit]
}

function headersOf(init: RequestInit): Record<string, string> {
  return (init.headers ?? {}) as Record<string, string>
}

function respond(body: unknown, status = 200) {
  return vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
  )
}

describe('sanctions review BFF', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('relays the operator bearer and posts to the review endpoint', async () => {
    global.fetch = respond({ id: 'check-1', status: 'CLEAR' })
    const { POST } = await import('../app/api/sanctions/review/route')

    const res = await POST(new NextRequest('http://localhost/api/sanctions/review', {
      method: 'POST',
      body: JSON.stringify({ checkId: 'check-1', note: 'false positive', newStatus: 'CLEAR' }),
    }))

    expect(res.status).toBe(200)
    const [url, init] = lastFetchCall()
    expect(url).toBe('http://openbank-sanctions-service:8123/api/v1/sanctions/review')
    expect(headersOf(init).Authorization).toBe('Bearer operator-token')
  })

  it('forwards X-Approval-Id to the upstream when the client supplies it', async () => {
    global.fetch = respond({ id: 'check-1', status: 'CLEAR' })
    const { POST } = await import('../app/api/sanctions/review/route')

    await POST(new NextRequest('http://localhost/api/sanctions/review', {
      method: 'POST',
      headers: { 'X-Approval-Id': 'approval-42' },
      body: JSON.stringify({ checkId: 'check-1', note: 'approved by a checker', newStatus: 'CLEAR' }),
    }))

    // The whole point of the header parameter on forwardToSanctionsService.
    expect(headersOf(lastFetchCall()[1])['X-Approval-Id']).toBe('approval-42')
  })

  it('omits X-Approval-Id entirely on a first submission', async () => {
    global.fetch = respond({ id: 'check-1', status: 'CLEAR' })
    const { POST } = await import('../app/api/sanctions/review/route')

    await POST(new NextRequest('http://localhost/api/sanctions/review', {
      method: 'POST',
      body: JSON.stringify({ checkId: 'check-1', note: 'first try', newStatus: 'CLEAR' }),
    }))

    expect(headersOf(lastFetchCall()[1])).not.toHaveProperty('X-Approval-Id')
  })

  it('passes a 202 four-eyes pause through with its body intact', async () => {
    // 202 is res.ok, so it must NOT be flattened into the generic upstream_error envelope —
    // the approvalId in this body is the only way back to that pending decision.
    global.fetch = respond({ status: 'PENDING_APPROVAL', approvalId: 'approval-42' }, 202)
    const { POST } = await import('../app/api/sanctions/review/route')

    const res = await POST(new NextRequest('http://localhost/api/sanctions/review', {
      method: 'POST',
      body: JSON.stringify({ checkId: 'check-1', note: 'needs a checker', newStatus: 'CLEAR' }),
    }))

    expect(res.status).toBe(202)
    await expect(res.json()).resolves.toEqual({ status: 'PENDING_APPROVAL', approvalId: 'approval-42' })
  })

  it('refuses without a session before touching the backend', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    global.fetch = respond({})
    const { POST } = await import('../app/api/sanctions/review/route')

    const res = await POST(new NextRequest('http://localhost/api/sanctions/review', {
      method: 'POST',
      body: JSON.stringify({ checkId: 'check-1', note: 'x', newStatus: 'CLEAR' }),
    }))

    expect(res.status).toBe(401)
    expect(global.fetch).not.toHaveBeenCalled()
  })
})

describe('sanctions approvals decide BFF (checker half)', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('PATCHes the approval with the operator bearer', async () => {
    global.fetch = respond({ id: 'approval-42', status: 'APPROVED' })
    const { PATCH } = await import('../app/api/sanctions/approvals/[id]/route')

    const res = await PATCH(
      new NextRequest('http://localhost/api/sanctions/approvals/approval-42', {
        method: 'PATCH', body: JSON.stringify({ approve: true }),
      }),
      { params: Promise.resolve({ id: 'approval-42' }) },
    )

    expect(res.status).toBe(200)
    const [url, init] = lastFetchCall()
    expect(url).toBe('http://openbank-sanctions-service:8123/api/v1/sanctions/approvals/approval-42')
    expect(init.method).toBe('PATCH')
    expect(headersOf(init).Authorization).toBe('Bearer operator-token')
  })

  it('preserves a 403 self-approval refusal rather than masking it as a generic failure', async () => {
    global.fetch = respond({ code: 'FORBIDDEN' }, 403)
    const { PATCH } = await import('../app/api/sanctions/approvals/[id]/route')

    const res = await PATCH(
      new NextRequest('http://localhost/api/sanctions/approvals/approval-42', {
        method: 'PATCH', body: JSON.stringify({ approve: true }),
      }),
      { params: Promise.resolve({ id: 'approval-42' }) },
    )

    // The status is what the UI keys its "you cannot decide your own request" message off.
    expect(res.status).toBe(403)
  })
})

describe('sanctions list routes relay the bearer too (#3336 leftovers)', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('PUT /lists/[id] sends Authorization', async () => {
    global.fetch = respond({ id: 'OFAC_SDN', enabled: false })
    const { PUT } = await import('../app/api/sanctions/lists/[id]/route')

    await PUT(
      new NextRequest('http://localhost/api/sanctions/lists/OFAC_SDN', {
        method: 'PUT', body: JSON.stringify({ enabled: false }),
      }),
      { params: Promise.resolve({ id: 'OFAC_SDN' }) },
    )

    expect(headersOf(lastFetchCall()[1]).Authorization).toBe('Bearer operator-token')
  })

  it('POST /lists/[id]/refresh sends Authorization', async () => {
    global.fetch = respond({ imported: 12 })
    const { POST } = await import('../app/api/sanctions/lists/[id]/refresh/route')

    await POST(
      new NextRequest('http://localhost/api/sanctions/lists/OFAC_SDN/refresh', { method: 'POST' }),
      { params: Promise.resolve({ id: 'OFAC_SDN' }) },
    )

    expect(headersOf(lastFetchCall()[1]).Authorization).toBe('Bearer operator-token')
  })
})
