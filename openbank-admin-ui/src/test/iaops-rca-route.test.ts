// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BFF proxy to HolmesGPT /api/chat (ADR-0031 D9). Tests pin input validation,
// upstream forwarding, response extraction and error paths without hitting the
// real HolmesGPT service.
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { NextRequest } from 'next/server'
import { auth } from '@/auth'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

function makeReq(body: unknown): NextRequest {
  return new NextRequest('http://localhost/api/iaops/rca', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

afterEach(() => {
  vi.restoreAllMocks()
  delete process.env.HOLMES_URL
})

beforeEach(() => {
  vi.mocked(auth).mockResolvedValue({ user: { roles: ['ROLE_OPERATOR'] } } as never)
})

describe('POST /api/iaops/rca', () => {
  it('refuses unauthenticated direct API calls before invoking HolmesGPT', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/rca/route')

    const res = await POST(makeReq({ ask: 'Why is transaction-service crashing?' }))

    expect(res.status).toBe(401)
    expect(await res.json()).toEqual({ error: 'unauthorized' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('refuses roles without the system:view permission', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { roles: ['ROLE_VIEWER'] } } as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/rca/route')

    const res = await POST(makeReq({ ask: 'Why is transaction-service crashing?' }))

    expect(res.status).toBe(403)
    expect(await res.json()).toEqual({ error: 'forbidden' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('returns 400 when ask is missing', async () => {
    vi.resetModules()
    const { POST } = await import('@/app/api/iaops/rca/route')
    const res = await POST(makeReq({}))
    expect(res.status).toBe(400)
    const body = await res.json()
    expect(body.error).toMatch(/required/i)
  })

  it('returns 400 when ask is too short', async () => {
    vi.resetModules()
    const { POST } = await import('@/app/api/iaops/rca/route')
    const res = await POST(makeReq({ ask: 'hi' }))
    expect(res.status).toBe(400)
  })

  it('forwards ask to HolmesGPT and returns rca from analysis field', async () => {
    process.env.HOLMES_URL = 'http://holmes-mock'
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ analysis: 'Pod OOMKilled due to memory leak in JVM heap.' }),
    }))
    const { POST } = await import('@/app/api/iaops/rca/route')
    const res = await POST(makeReq({ ask: 'Why is transaction-service crashing?' }))
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.rca).toBe('Pod OOMKilled due to memory leak in JVM heap.')
    expect(vi.mocked(fetch)).toHaveBeenCalledWith(
      'http://holmes-mock/api/chat',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('extracts rca from response field as fallback', async () => {
    process.env.HOLMES_URL = 'http://holmes-mock'
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ response: 'CPU throttling caused latency spike.' }),
    }))
    const { POST } = await import('@/app/api/iaops/rca/route')
    const body = await (await POST(makeReq({ ask: 'Latency alert on fx-service' }))).json()
    expect(body.rca).toBe('CPU throttling caused latency spike.')
  })

  it('returns a safe envelope on a non-ok upstream response', async () => {
    process.env.HOLMES_URL = 'http://holmes-mock'
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      text: async () => 'internal upstream diagnostic',
    }))
    const { POST } = await import('@/app/api/iaops/rca/route')
    const res = await POST(makeReq({ ask: 'Alert: PodCrashLooping on balance-service' }))
    expect(res.status).toBe(502)
    const body = await res.json()
    expect(body).toEqual({ error: 'upstream_error' })
  })

  it('returns 502 on network failure', async () => {
    process.env.HOLMES_URL = 'http://holmes-mock'
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')))
    const { POST } = await import('@/app/api/iaops/rca/route')
    const res = await POST(makeReq({ ask: 'Alert: PodCrashLooping on balance-service' }))
    expect(res.status).toBe(502)
    const body = await res.json()
    expect(body).toEqual({ error: 'upstream_error' })
  })
})
