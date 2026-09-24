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
  delete process.env.CASE_COORDINATOR_URL
})

beforeEach(() => {
  vi.mocked(auth).mockResolvedValue({
    user: { roles: ['ROLE_OPERATOR'], accessToken: 'operator-token' },
  } as never)
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

  it('forwards ask to HolmesGPT and records the RCA in a shadow case', async () => {
    process.env.HOLMES_URL = 'http://holmes-mock'
    process.env.CASE_COORDINATOR_URL = 'http://case-coordinator-mock'
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ analysis: 'Pod OOMKilled due to memory leak in JVM heap.' }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 201,
        json: async () => ({ caseId: 'case-incident-response-alert-a1b2c3d4' }),
      })
      .mockResolvedValueOnce({ ok: true, status: 202 })
      .mockResolvedValueOnce({ ok: true, status: 202 }))
    const { POST } = await import('@/app/api/iaops/rca/route')
    const res = await POST(makeReq({ ask: 'Why is transaction-service crashing?' }))
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.rca).toBe('Pod OOMKilled due to memory leak in JVM heap.')
    expect(body.shadowCase).toEqual({
      caseId: 'case-incident-response-alert-a1b2c3d4',
      recorded: true,
    })
    expect(vi.mocked(fetch)).toHaveBeenCalledWith(
      'http://holmes-mock/api/chat',
      expect.objectContaining({ method: 'POST' }),
    )
    expect(vi.mocked(fetch)).toHaveBeenNthCalledWith(
      2,
      'http://case-coordinator-mock/api/v1/case-coordinator/cases',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ Authorization: 'Bearer operator-token' }),
      }),
    )
    expect(vi.mocked(fetch)).toHaveBeenNthCalledWith(
      4,
      'http://case-coordinator-mock/api/v1/case-coordinator/cases/case-incident-response-alert-a1b2c3d4/signals',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('returns the RCA honestly when shadow-case recording fails', async () => {
    process.env.HOLMES_URL = 'http://holmes-mock'
    process.env.CASE_COORDINATOR_URL = 'http://case-coordinator-mock'
    vi.mocked(auth).mockResolvedValue({
      user: { roles: ['ROLE_OPERATOR'], accessToken: 'operator-token' },
    } as never)
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ analysis: 'Temporal worker is unavailable.' }),
      })
      .mockResolvedValueOnce({ ok: false, status: 503 }))

    const { POST } = await import('@/app/api/iaops/rca/route')
    const res = await POST(makeReq({ ask: 'Why did the workflow worker disappear?' }))

    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({
      rca: 'Temporal worker is unavailable.',
      shadowCase: { recorded: false, reason: 'unavailable' },
    })
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(2)
  })

  it('reuses the deterministic case when the alert was already opened', async () => {
    process.env.HOLMES_URL = 'http://holmes-mock'
    process.env.CASE_COORDINATOR_URL = 'http://case-coordinator-mock'
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ analysis: 'Repeated alert still points to the same worker.' }),
      })
      .mockResolvedValueOnce({ ok: false, status: 409 })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ status: 'OPEN' }) })
      .mockResolvedValueOnce({ ok: true, status: 202 })
      )

    const { POST } = await import('@/app/api/iaops/rca/route')
    const res = await POST(makeReq({ ask: 'Repeated Temporal worker alert' }))
    const body = await res.json()

    expect(res.status).toBe(200)
    expect(body.shadowCase).toMatchObject({ recorded: true })
    expect(body.shadowCase.caseId).toMatch(/^case-incident-response-rca-[a-f0-9]{16}$/)
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(4)
  })

  it('does not signal a historical closed case after duplicate open', async () => {
    process.env.HOLMES_URL = 'http://holmes-mock'
    process.env.CASE_COORDINATOR_URL = 'http://case-coordinator-mock'
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ analysis: 'Historical RCA.' }) })
      .mockResolvedValueOnce({ ok: false, status: 409 })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ status: 'CLOSED' }) }))

    const { POST } = await import('@/app/api/iaops/rca/route')
    const body = await (await POST(makeReq({ ask: 'A completed historical alert' }))).json()

    expect(body.shadowCase).toEqual({ recorded: false, reason: 'case_closed' })
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(3)
  })

  it('reports backend authorization denial distinctly from unavailability', async () => {
    process.env.HOLMES_URL = 'http://holmes-mock'
    process.env.CASE_COORDINATOR_URL = 'http://case-coordinator-mock'
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ analysis: 'Denied RCA.' }) })
      .mockResolvedValueOnce({ ok: false, status: 403 }))

    const { POST } = await import('@/app/api/iaops/rca/route')
    const body = await (await POST(makeReq({ ask: 'An alert without a grant' }))).json()

    expect(body.shadowCase).toEqual({ recorded: false, reason: 'not_authorized' })
  })

  it('does not open a case when a read-only demo user requests RCA', async () => {
    process.env.HOLMES_URL = 'http://holmes-mock'
    process.env.CASE_COORDINATOR_URL = 'http://case-coordinator-mock'
    vi.mocked(auth).mockResolvedValue({
      user: { roles: ['ROLE_DEMO'], accessToken: 'demo-token' },
    } as never)
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ analysis: 'Read-only RCA result.' }),
    }))

    const { POST } = await import('@/app/api/iaops/rca/route')
    const res = await POST(makeReq({ ask: 'Why is the service unavailable?' }))

    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({
      rca: 'Read-only RCA result.',
      shadowCase: { recorded: false, reason: 'not_authorized' },
    })
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1)
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
