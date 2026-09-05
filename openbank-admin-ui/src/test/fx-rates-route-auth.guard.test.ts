// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

/**
 * `GET /api/v1/fx/rates` on fx-service is
 * `@RolesAllowed(ROLE_VIEWER, ROLE_OPERATOR, ROLE_ADMIN, ROLE_PAYMENTS)`.
 * This BFF route used to call it with **no Authorization header at all**, and answered
 * `if (!res.ok) return []` — so 401 was not an edge case, it was the expected response, and it
 * rendered as "no rates". A missing credential, a 403, a timeout and a genuinely empty rate table
 * were four states collapsed into one value.
 *
 * Two properties are pinned here, and they fail for different reasons on purpose:
 *   1. the operator bearer is relayed (so the call can succeed at all);
 *   2. a failure is REPORTED rather than rendered as emptiness (so nobody has to guess).
 *
 * The second is the one that matters longer term: restoring the header without restoring the
 * error field would give back a working call whose next failure is silent again.
 */

const mockAuth = vi.fn()
vi.mock('@/auth', () => ({ auth: () => mockAuth() }))
vi.mock('@/lib/discovery', () => ({
  inCluster: () => true,
  discoverServices: async () => ([{ name: 'fx-service', namespace: 'fx', ready: 1, desired: 1 }]),
  resolveInClusterBaseUrl: async () => 'http://fx-service.fx.svc:8119',
}))

async function callRoute() {
  const mod = await import('@/app/api/fx/rates/route')
  const res = await mod.GET()
  return res.json() as Promise<Record<string, any>>
}

describe('GET /api/fx/rates relays the operator bearer', () => {
  let fetchSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.resetModules()
    fetchSpy = vi.fn(async (url: string) => {
      if (String(url).includes('/q/health/ready')) return new Response('', { status: 200 })
      if (String(url).includes('/api/v1/fx/')) return new Response(JSON.stringify([]), { status: 200 })
      return new Response('[]', { status: 200 })
    })
    vi.stubGlobal('fetch', fetchSpy)
  })
  afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

  it('sends Authorization on every fx-service call when a session exists', async () => {
    mockAuth.mockResolvedValue({ user: { accessToken: 'operator-token' } })
    await callRoute()

    const fxCalls = fetchSpy.mock.calls.filter(c => String(c[0]).includes('/api/v1/fx/'))
    expect(fxCalls.length).toBeGreaterThan(0)   // if zero, the test proves nothing
    for (const [, init] of fxCalls) {
      const headers = (init as RequestInit | undefined)?.headers as Record<string, string> | undefined
      expect(headers?.Authorization).toBe('Bearer operator-token')
    }
  })

  it('reports an unauthenticated read instead of rendering it as an empty table', async () => {
    mockAuth.mockResolvedValue(null)
    const body = await callRoute()

    // The distinguishing property: empty rows AND a stated reason.
    expect(body.fxService.rates).toEqual([])
    expect(body.fxService.error).toMatch(/unauthenticated/i)
  })

  it('reports a non-OK upstream status rather than swallowing it', async () => {
    mockAuth.mockResolvedValue({ user: { accessToken: 'operator-token' } })
    fetchSpy.mockImplementation(async (url: string) => {
      if (String(url).includes('/q/health/ready')) return new Response('', { status: 200 })
      if (String(url).includes('/api/v1/fx/')) return new Response('denied', { status: 403 })
      return new Response('[]', { status: 200 })
    })
    const body = await callRoute()

    expect(body.fxService.rates).toEqual([])
    expect(body.fxService.error).toContain('403')
  })

  it('reports no error on a genuinely empty but successful read', async () => {
    // The control that makes the two assertions above mean something: emptiness on its own
    // must NOT be reported as a failure, or "error" would just track "rows.length === 0".
    mockAuth.mockResolvedValue({ user: { accessToken: 'operator-token' } })
    const body = await callRoute()

    expect(body.fxService.rates).toEqual([])
    expect(body.fxService.error).toBeNull()
  })
})
