// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { auth } from '@/auth'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

describe('GET /api/finops/anomalies', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue({ user: { roles: ['ROLE_OPERATOR'] } } as never)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    delete process.env.ALERTMANAGER_URL
  })

  it('returns 401 and never contacts Alertmanager without a session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/finops/anomalies/route')

    const res = await GET()

    expect(res.status).toBe(401)
    await expect(res.json()).resolves.toEqual({ error: 'unauthorized' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('returns 403 and never contacts Alertmanager without system:view', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { roles: ['ROLE_VIEWER'] } } as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/finops/anomalies/route')

    const res = await GET()

    expect(res.status).toBe(403)
    await expect(res.json()).resolves.toEqual({ error: 'forbidden' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('returns mapped anomalies for an authorized system operator', async () => {
    const signal = new AbortController().signal
    vi.spyOn(AbortSignal, 'timeout').mockReturnValue(signal)
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify([{
      labels: { alertname: 'IdleService', detector: 'D2', severity: 'warning' },
      annotations: { summary: 'Idle service detected' },
      startsAt: '2026-09-01T09:00:00Z',
      status: { state: 'active' },
    }]), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/finops/anomalies/route')

    const res = await GET()

    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toMatchObject({
      available: true,
      anomalies: [{ detector: 'D2', title: 'Idle service detected', status: 'open' }],
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][1]?.signal).toBe(signal)
  })

  it('bounds Alertmanager and degrades a timeout to the established calm state', async () => {
    const signal = new AbortController().signal
    const timeout = vi.spyOn(AbortSignal, 'timeout').mockReturnValue(signal)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('timed out', 'TimeoutError')))
    const { GET } = await import('@/app/api/finops/anomalies/route')

    const res = await GET()

    expect(timeout).toHaveBeenCalledWith(5_000)
    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toEqual({ anomalies: [], available: false })
  })
})
