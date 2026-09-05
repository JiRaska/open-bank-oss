// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

const REQUEST_DAY = '2026-08-18'

function triggerRequest(requestedOn: unknown = REQUEST_DAY) {
  return new Request('http://localhost/api/iaops/flaky-test-hunter/trigger', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ requestedOn }),
  })
}

describe('flaky-test-hunter trigger BFF', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-18T12:00:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('drives the real BFF through the provider-replayed Admin UI Pact interaction', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    const pact = JSON.parse(await readFile(
      resolve(process.cwd(), '../pacts/openbank-admin-ui-openbank-flaky-test-hunter.json'), 'utf8',
    )) as { interactions: Array<{ request: { method: string; path: string; headers?: Record<string, string | string[]> }; response: { status: number; body: unknown } }> }
    const interaction = pact.interactions.find(item => item.request.method === 'POST' && item.request.path === '/api/v1/flaky-test-hunter/check/trigger-async-idempotent')
    if (!interaction) throw new Error('flaky-test trigger interaction is missing from the committed Pact')
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ workflowId: 'flaky-test-hunter-check-operator_manual-2026-08-18' }), { status: 202 }))
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST(triggerRequest())

    expect(response.status).toBe(interaction.response.status)
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.method).toBe(interaction.request.method)
    expect(new URL(url).pathname).toBe(interaction.request.path)
    expect(new URL(url).search).toBe('')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe('flaky-test-hunter-operator-manual-2026-08-18')
    expect(interaction.request.headers?.['Idempotency-Key']).toEqual('flaky-test-hunter-operator-manual-2026-08-18')
    await expect(response.json()).resolves.toEqual(interaction.response.body)
  })

  it('rejects a non-admin session before it contacts the agent', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'viewer-token', roles: ['ROLE_VIEWER'] } } as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST(triggerRequest())

    expect(response.status).toBe(403)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects a missing session before it contacts the agent', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST(triggerRequest())

    expect(response.status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each([
    ['timeout', new DOMException('Timed out', 'TimeoutError')],
    ['abort', new DOMException('Aborted', 'AbortError')],
    ['connection loss', new TypeError('fetch failed')],
  ])('reports %s after POST as an unknown admission outcome', async (_label, error) => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(error))
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST(triggerRequest())

    expect(response.status).toBe(_label === 'connection loss' ? 502 : 504)
    await expect(response.json()).resolves.toEqual({
      error: 'admission_outcome_unknown',
      cause: _label === 'connection loss' ? 'network' : 'timeout',
      requestedOn: REQUEST_DAY,
    })
  })

  it.each([
    ['missing id', '{}'],
    ['empty id', '{"workflowId":""}'],
    ['whitespace id', '{"workflowId":"   "}'],
    ['wrong-type id', '{"workflowId":42}'],
    ['wrong-day id', '{"workflowId":"flaky-test-hunter-check-operator_manual-2026-08-17"}'],
    ['nonconforming id', '{"workflowId":"other-workflow"}'],
    ['malformed JSON', '{'],
  ])('does not authorize a retry for a 202 response with %s', async (_label, payload) => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(payload, { status: 202 })))
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST(triggerRequest())

    expect(response.status).toBe(502)
    await expect(response.json()).resolves.toEqual({
      error: 'admission_accepted_handle_unknown',
      cause: 'invalid_response',
      requestedOn: REQUEST_DAY,
    })
  })

  it.each([
    ['unexpected success', 200],
    ['request timeout', 408],
    ['workflow conflict', 409],
    ['upstream failure', 503],
  ])('does not treat %s status %i as proof that admission failed', async (label, status) => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ workflowId: 'possibly-started' }), { status })))
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST(triggerRequest())

    const expectedStatus = status === 408 ? 504 : 502
    const cause = label === 'unexpected success'
      ? 'unexpected_status'
      : label === 'request timeout'
        ? 'upstream_timeout'
        : label === 'workflow conflict'
          ? 'conflict'
          : 'upstream_status'
    expect(response.status).toBe(expectedStatus)
    await expect(response.json()).resolves.toEqual({
      error: 'admission_outcome_unknown',
      cause,
      requestedOn: REQUEST_DAY,
      upstreamStatus: status,
    })
  })

  it('fails closed on a pre-expand backend and never falls back to the legacy trigger', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    const fetchMock = vi.fn().mockResolvedValue(new Response('', { status: 404 }))
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST(triggerRequest())

    expect(response.status).toBe(503)
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe('/api/v1/flaky-test-hunter/check/trigger-async-idempotent')
    await expect(response.json()).resolves.toEqual({
      error: 'idempotent_admission_not_supported',
      requestedOn: REQUEST_DAY,
      upstreamStatus: 404,
    })
  })

  it('preserves a deterministic upstream client rejection without leaking its body', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('sensitive upstream detail', { status: 422 })))
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST(triggerRequest())

    expect(response.status).toBe(422)
    await expect(response.json()).resolves.toEqual({ error: 'admission_rejected', upstreamStatus: 422 })
  })

  it.each([
    ['missing', undefined],
    ['malformed', '2026/08/18'],
    ['future', '2026-08-19'],
    ['too old', '2026-08-16'],
  ])('rejects a %s recovery day before contacting the agent', async (_label, requestedOn) => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')
    const request = requestedOn === undefined
      ? new Request('http://localhost/api/iaops/flaky-test-hunter/trigger', { method: 'POST', body: '{}' })
      : triggerRequest(requestedOn)

    const response = await POST(request)

    expect(response.status).toBe(400)
    await expect(response.json()).resolves.toEqual({ error: 'invalid_idempotency_day' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('accepts yesterday only to recover the same workflow across UTC midnight', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      workflowId: 'flaky-test-hunter-check-operator_manual-2026-08-17',
    }), { status: 202 }))
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST(triggerRequest('2026-08-17'))

    expect(response.status).toBe(202)
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe('flaky-test-hunter-operator-manual-2026-08-17')
  })

  it('retires the legacy synchronous BFF route without contacting the agent', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/flaky-test-hunter/trigger/route')

    const response = await POST()

    expect(response.status).toBe(410)
    await expect(response.json()).resolves.toEqual({
      error: 'legacy_trigger_retired',
      replacement: '/api/iaops/flaky-test-hunter/trigger',
    })
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
