// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { auth } from '@/auth'

vi.mock('@/auth', () => ({ auth: vi.fn(async () => ({ user: { accessToken: 'token' } })) }))
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('referral program drafts BFF', () => {
  it('answers 401 without a session, before touching the service', async () => {
    vi.mocked(auth).mockResolvedValueOnce(null as never)
    const upstream = vi.fn()
    vi.stubGlobal('fetch', upstream)
    const { GET } = await import('@/app/api/referral-programs/drafts/route')

    const response = await GET()
    expect(response.status).toBe(401)
    expect(await response.json()).toEqual({ error: 'unauthenticated' })
    expect(upstream).not.toHaveBeenCalled()
  })

  it('relays the operator token to the drafts endpoint', async () => {
    const upstream = vi.fn(async () => Response.json([
      { id: 'd-1', name: 'friends-autumn', version: 1, status: 'DRAFT', maker: 'marketing@openbank.local' },
    ]))
    vi.stubGlobal('fetch', upstream)
    const { GET } = await import('@/app/api/referral-programs/drafts/route')

    const response = await GET()
    expect(await response.json()).toEqual({
      items: [{ id: 'd-1', name: 'friends-autumn', version: 1, status: 'DRAFT', maker: 'marketing@openbank.local' }],
      state: 'ok',
    })
    expect(upstream).toHaveBeenCalledWith('http://localhost:8155/api/v1/referrals/programs/drafts', expect.objectContaining({
      headers: { authorization: 'Bearer token' }, cache: 'no-store',
    }))
  })

  it('does not reinterpret an upstream refusal as an empty queue', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 403 })))
    const { GET } = await import('@/app/api/referral-programs/drafts/route')
    expect(await (await GET()).json()).toEqual({ items: [], state: 'unauthorized' })
  })

  it('reports a missing drafts endpoint as not_deployed', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })))
    const { GET } = await import('@/app/api/referral-programs/drafts/route')
    expect(await (await GET()).json()).toEqual({ items: [], state: 'not_deployed' })
  })

  it('answers unreachable when the service cannot be reached at all', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('ECONNREFUSED') }))
    const { GET } = await import('@/app/api/referral-programs/drafts/route')
    expect(await (await GET()).json()).toEqual({ items: [], state: 'unreachable' })
  })
})
