// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

describe('flaky-test-hunter trigger BFF', () => {
  beforeEach(() => vi.resetModules())
  afterEach(() => vi.restoreAllMocks())

  it('drives the real BFF through the provider-replayed Admin UI Pact interaction', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    const pact = JSON.parse(await readFile(
      resolve(process.cwd(), '../pacts/openbank-admin-ui-openbank-flaky-test-hunter.json'), 'utf8',
    )) as { interactions: Array<{ request: { method: string; path: string; headers?: Record<string, string | string[]> }; response: { body: unknown } }> }
    const interaction = pact.interactions.find(item => item.request.method === 'POST' && item.request.path === '/api/v1/flaky-test-hunter/check/trigger-async')
    if (!interaction) throw new Error('flaky-test trigger interaction is missing from the committed Pact')
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ workflowId: 'flaky-test-check-operator_manual-2026-08-18' }), { status: 202 }))
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST()

    expect(response.status).toBe(202)
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(interaction.request.path)
    expect(new URL(url).search).toBe('')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer operator-token')
    await expect(response.json()).resolves.toEqual(interaction.response.body)
  })

  it('rejects a non-admin session before it contacts the agent', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'viewer-token', roles: ['ROLE_VIEWER'] } } as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST()

    expect(response.status).toBe(403)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects a missing session before it contacts the agent', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST()

    expect(response.status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('reports a timed-out admission as unknown rather than as an unavailable service', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('Timed out', 'TimeoutError')))
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST()

    expect(response.status).toBe(504)
    await expect(response.json()).resolves.toEqual({ error: 'admission_outcome_unknown' })
  })

  it('does not report success for an accepted response without a workflow id', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 202 })))
    const { POST } = await import('@/app/api/iaops/flaky-test-hunter/trigger/route')

    const response = await POST()

    expect(response.status).toBe(502)
    await expect(response.json()).resolves.toEqual({ error: 'upstream_invalid_response' })
  })
})
