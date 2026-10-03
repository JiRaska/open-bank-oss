// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'
import * as sca from '@/app/api/sca/approvals/[id]/route'
import * as settlement from '@/app/api/settlements/approvals/[id]/route'

const id = 'bde18d9e-3e49-4f4e-98cc-a56646ccbc61'
const context = { params: Promise.resolve({ id }) }
const session = { user: { accessToken: 'test-operator-token', roles: ['ROLE_OPERATOR'] } }
const request = (body = '{"approve":true}') => new Request(`http://localhost/api/x/approvals/${id}`, {
  method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body,
})

beforeEach(() => { vi.mocked(auth).mockResolvedValue(session as never) })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe.each([
  ['sca', sca, '/api/v1/sca/approvals/', ':8110'],
  ['settlement', settlement, '/api/v1/settlements/approvals/', ':8138'],
] as const)('%s operator approval BFF', (_name, route, upstreamPath, port) => {
  it('refuses missing sessions and non-operator roles before any upstream call', async () => {
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    vi.mocked(auth).mockResolvedValue(null as never)
    expect((await route.GET(request(), context)).status).toBe(401)
    expect((await route.PATCH(request(), context)).status).toBe(401)
    // The services answer 403 to everyone but OPERATOR/ADMIN; compliance sees the inbox only.
    for (const role of ['ROLE_COMPLIANCE', 'ROLE_VIEWER', 'ROLE_CUSTOMER', 'ROLE_API']) {
      vi.mocked(auth).mockResolvedValue({ user: { ...session.user, roles: [role] } } as never)
      expect((await route.PATCH(request(), context)).status).toBe(403)
      expect((await route.GET(request(), context)).status).toBe(403)
    }
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('relays only the operator bearer and the explicit boolean decision', async () => {
    const fetcher = vi.fn().mockResolvedValue(Response.json({ id, status: 'REJECTED' }))
    vi.stubGlobal('fetch', fetcher)
    expect((await route.PATCH(request('{"approve":false,"makerId":"forged","checker":"x"}'), context)).status).toBe(200)
    const [url, init] = fetcher.mock.calls[0]
    expect(url).toContain(`${port}${upstreamPath}${id}`)
    expect(new Headers(init.headers).get('authorization')).toBe('Bearer test-operator-token')
    expect(JSON.parse(init.body)).toEqual({ approve: false })
    expect(init.method).toBe('PATCH')
  })

  it('rejects invalid ids and malformed or non-boolean decisions without forwarding', async () => {
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    expect((await route.GET(request(), { params: Promise.resolve({ id: '../other' }) })).status).toBe(400)
    for (const body of ['{', 'null', '{}', '{"approve":"true"}']) {
      expect((await route.PATCH(request(body), context)).status).toBe(400)
    }
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('keeps a useful status without exposing upstream details', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('private upstream detail', { status: 409 })))
    const conflict = await route.PATCH(request(), context)
    expect(conflict.status).toBe(409)
    expect(await conflict.text()).not.toContain('private upstream detail')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('boom', { status: 500 })))
    expect((await route.GET(request(), context)).status).toBe(502)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('private host')))
    const unreachable = await route.GET(request(), context)
    expect(unreachable.status).toBe(502)
    expect(await unreachable.text()).not.toContain('private host')
  })
})
