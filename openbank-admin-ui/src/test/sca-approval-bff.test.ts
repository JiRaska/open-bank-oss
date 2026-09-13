// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'
import { GET, PATCH } from '@/app/api/sca/approvals/[id]/route'

const id = 'bde18d9e-3e49-4f4e-98cc-a56646ccbc61'
const context = { params: Promise.resolve({ id }) }
const session = { user: { accessToken: 'test-operator-token', roles: ['ROLE_OPERATOR'] } }
const request = (body = '{"approve":true}') => new Request(`http://localhost/api/sca/approvals/${id}`, {
  method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body,
})

beforeEach(() => { vi.mocked(auth).mockResolvedValue(session as never) })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('SCA approval BFF', () => {
  it('refuses missing sessions and unauthorized roles before any upstream call', async () => {
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    vi.mocked(auth).mockResolvedValue(null as never)
    expect((await GET(request(), context)).status).toBe(401)
    expect((await PATCH(request(), context)).status).toBe(401)
    vi.mocked(auth).mockResolvedValue({ user: { ...session.user, roles: ['ROLE_CUSTOMER'] } } as never)
    expect((await PATCH(request(), context)).status).toBe(403)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('relays only the operator bearer and explicit boolean decision', async () => {
    const fetcher = vi.fn().mockResolvedValue(Response.json({ id, status: 'REJECTED' }))
    vi.stubGlobal('fetch', fetcher)
    expect((await PATCH(request('{"approve":false,"makerId":"forged"}'), context)).status).toBe(200)
    const [url, init] = fetcher.mock.calls[0]
    expect(url).toContain(`/api/v1/sca/approvals/${id}`)
    expect(new Headers(init.headers).get('authorization')).toBe('Bearer test-operator-token')
    expect(JSON.parse(init.body)).toEqual({ approve: false })
    expect(init.method).toBe('PATCH')
  })

  it('rejects invalid ids and malformed or nonboolean decisions without forwarding', async () => {
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    expect((await GET(request(), { params: Promise.resolve({ id: '../other' }) })).status).toBe(400)
    for (const body of ['{', 'null', '{}', '{"approve":"true"}']) {
      expect((await PATCH(request(body), context)).status).toBe(400)
    }
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('keeps useful error status without exposing upstream details', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('private upstream detail', { status: 409 })))
    const response = await PATCH(request(), context)
    expect(response.status).toBe(409)
    expect(await response.text()).not.toContain('private upstream detail')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('private host')))
    const unavailable = await GET(request(), context)
    expect(unavailable.status).toBe(502)
    expect(await unavailable.text()).not.toContain('private host')
  })
})
