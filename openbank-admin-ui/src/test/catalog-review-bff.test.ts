// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

const OFFERING_ID = '10000000-0000-0000-0000-000000000001'
const REVISION_ID = '20000000-0000-0000-0000-000000000001'

function request(body: unknown) {
  return new NextRequest('http://localhost/api/agent/catalog-reviews', {
    method: 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' },
  })
}

describe('catalog review BFF', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token' } } as never)
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('relays only exact aggregate identifiers and the operator bearer to the governed review endpoint', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ proposalId: 'p-1', state: 'PROPOSED' }), { status: 201 }))
    vi.stubGlobal('fetch', fetcher)
    const { POST } = await import('@/app/api/agent/catalog-reviews/route')

    const response = await POST(request({ offeringId: OFFERING_ID, revisionId: REVISION_ID, model: 'self-hosted-review' }))

    expect(response.status).toBe(201)
    expect(fetcher).toHaveBeenCalledWith('http://localhost:8109/agent/catalog-reviews', expect.objectContaining({
      method: 'POST', headers: expect.objectContaining({ Authorization: 'Bearer operator-token' }),
      body: JSON.stringify({ offeringId: OFFERING_ID, revisionId: REVISION_ID, model: 'self-hosted-review' }),
    }))
  })

  it('does not contact agent-service when the browser session is absent', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    const { POST } = await import('@/app/api/agent/catalog-reviews/route')

    const response = await POST(request({ offeringId: OFFERING_ID, revisionId: REVISION_ID }))

    expect(response.status).toBe(401)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('rejects an incomplete request before contacting the review model', async () => {
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    const { POST } = await import('@/app/api/agent/catalog-reviews/route')

    const response = await POST(request({ offeringId: OFFERING_ID }))

    expect(response.status).toBe(400)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('preserves an upstream model-unavailable result for an honest UI state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'model unavailable' }), { status: 503 })))
    const { POST } = await import('@/app/api/agent/catalog-reviews/route')

    const response = await POST(request({ offeringId: OFFERING_ID, revisionId: REVISION_ID }))

    expect(response.status).toBe(503)
    await expect(response.json()).resolves.toEqual({ error: 'model unavailable' })
  })
})
