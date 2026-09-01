// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

/**
 * fx-service guards `GET /api/v1/fx/rates/{base}/{quote}/history` with
 * `@RolesAllowed(ROLE_VIEWER, ROLE_OPERATOR, ROLE_ADMIN, ROLE_PAYMENTS)`. This BFF route called it
 * with no `Authorization` header, so in a deployed cluster every request would have answered 401 and
 * this endpoint would have returned `upstream_error` for all of them — the trend would never have
 * rendered once. Sibling of the same defect fixed for `/api/fx/rates`.
 *
 * Three properties are pinned, and they fail for different reasons on purpose:
 *   1. the bearer is relayed on EVERY fx-service call, the inverse-pair retry included;
 *   2. no session answers 401, not 502 — "you are not authenticated" and "the upstream failed" are
 *      different problems, and folding them together makes the first one unreportable;
 *   3. a real upstream failure still answers 502, so property 2 cannot be satisfied by
 *      relabelling everything.
 *
 * The fourth test is the control that gives the others meaning: a successful but EMPTY history is a
 * 200 with no points, never an error — otherwise "error" would just be tracking `points.length === 0`.
 */

const mockAuth = vi.fn()
vi.mock('@/auth', () => ({ auth: () => mockAuth() }))
vi.mock('@/lib/discovery', () => ({
  inCluster: () => true,
  discoverServices: async () => ([{ name: 'fx-service', namespace: 'fx', ready: true, scaledToZero: false }]),
  resolveInClusterBaseUrl: async () => 'http://fx-service.fx.svc:8119',
}))

async function callRoute(query = '?base=EUR&quote=CZK') {
  const mod = await import('@/app/api/fx/history/route')
  const { NextRequest } = await import('next/server')
  const res = await mod.GET(new NextRequest(`http://admin.local/api/fx/history${query}`))
  return { status: res.status, body: (await res.json()) as Record<string, any> }
}

const HISTORY = '/api/v1/fx/rates/'

describe('GET /api/fx/history relays the operator bearer', () => {
  let fetchSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.resetModules()
    fetchSpy = vi.fn(async (url: string) => new Response(JSON.stringify([]), { status: 200 }))
    vi.stubGlobal('fetch', fetchSpy)
  })
  afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

  it('sends Authorization on every fx-service history call, inverse-pair retry included', async () => {
    mockAuth.mockResolvedValue({ user: { accessToken: 'operator-token' } })
    await callRoute()

    const calls = fetchSpy.mock.calls.filter(c => String(c[0]).includes(HISTORY))
    // An empty direct response triggers the inverse-pair retry, so both calls are exercised here.
    expect(calls.length).toBeGreaterThanOrEqual(2)   // if zero, the test proves nothing
    for (const [, init] of calls) {
      const headers = (init as RequestInit | undefined)?.headers as Record<string, string> | undefined
      expect(headers?.Authorization).toBe('Bearer operator-token')
    }
  })

  it('answers 401 with a stated reason when there is no session, and calls nobody', async () => {
    mockAuth.mockResolvedValue(null)
    const { status, body } = await callRoute()

    expect(status).toBe(401)
    expect(body.error).toMatch(/unauthenticated/i)
    expect(fetchSpy.mock.calls.filter(c => String(c[0]).includes(HISTORY))).toHaveLength(0)
  })

  it('still answers 502 for a genuine upstream failure', async () => {
    mockAuth.mockResolvedValue({ user: { accessToken: 'operator-token' } })
    fetchSpy.mockImplementation(async (url: string) =>
      String(url).includes(HISTORY) ? new Response('denied', { status: 403 }) : new Response('[]', { status: 200 }))

    const { status, body } = await callRoute()
    expect(status).toBe(502)
    expect(body.error).toBe('upstream_error')
  })

  it('treats a successful but empty history as success, not as an error', async () => {
    mockAuth.mockResolvedValue({ user: { accessToken: 'operator-token' } })
    const { status, body } = await callRoute()

    expect(status).toBe(200)
    expect(body.points).toEqual([])
    expect(body.error).toBeUndefined()
  })
})
