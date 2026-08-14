// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } }

function lastFetchCall() {
  const mock = vi.mocked(global.fetch)
  return mock.mock.calls[mock.mock.calls.length - 1] as [string, RequestInit]
}

describe('product-catalog BFF bearer relay', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => vi.restoreAllMocks())

  it('relays the operator bearer on lifecycle mutation and keeps the id in one path segment', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'ACTIVE' }), { status: 200 })))
    const { POST } = await import('@/app/api/product-catalog/[id]/activate/route')

    const response = await POST(new NextRequest('http://localhost/api/product-catalog/a/activate', { method: 'POST' }), {
      params: Promise.resolve({ id: 'a/b' }),
    })

    expect(response.status).toBe(200)
    const [url, init] = lastFetchCall()
    expect(String(url)).toContain('/api/v1/products/a%2Fb/activate')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
  })

  it('refuses a request with no session without calling product-catalog', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/product-catalog/fees/route')

    const response = await GET(new NextRequest('http://localhost/api/product-catalog/fees'))

    expect(response.status).toBe(401)
    await expect(response.json()).resolves.toEqual({ error: 'unauthorized' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('does not expose an upstream failure body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('catalog database unavailable', { status: 500 })))
    const { GET } = await import('@/app/api/product-catalog/route')

    const response = await GET(new NextRequest('http://localhost/api/product-catalog'))

    expect(response.status).toBe(500)
    await expect(response.json()).resolves.toEqual({ error: 'upstream_error' })
  })
})
