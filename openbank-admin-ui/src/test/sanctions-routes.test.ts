// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { afterEach, describe, expect, it, vi } from 'vitest'

describe('sanctions api routes', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('checks route forwards backend errors instead of returning empty fallback', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'backend down' }), {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
    })))

    const { GET } = await import('../app/api/sanctions/checks/route')
    const response = await GET(new Request('http://localhost/api/sanctions/checks') as any)

    expect(response.status).toBe(503)
    await expect(response.json()).resolves.toEqual({ error: 'backend down' })
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
    }) as any)

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
