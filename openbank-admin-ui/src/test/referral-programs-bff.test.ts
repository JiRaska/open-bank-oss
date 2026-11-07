// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { auth } from '@/auth'

vi.mock('@/auth', () => ({ auth: vi.fn(async () => ({ user: { accessToken: 'token' } })) }))
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('referral program catalogue BFF', () => {
  it('relays the operator token and preserves immutable published versions', async () => {
    const upstream = vi.fn(async () => Response.json([{ id: 'p-1', name: 'friends', version: 2 }]))
    vi.stubGlobal('fetch', upstream)
    const { GET } = await import('@/app/api/referral-programs/route')

    const response = await GET()
    expect(await response.json()).toEqual({ items: [{ id: 'p-1', name: 'friends', version: 2 }], state: 'ok' })
    expect(upstream).toHaveBeenCalledWith('http://localhost:8155/api/v1/referrals/programs', expect.objectContaining({
      headers: { authorization: 'Bearer token' }, cache: 'no-store',
    }))
  })

  it('does not reinterpret a malformed upstream catalogue as an empty published one', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json([{ id: 'p-1', name: 'friends' }])))
    const { GET } = await import('@/app/api/referral-programs/route')
    expect(await (await GET()).json()).toEqual({ items: [], state: 'unreachable' })
  })

  it('projects a valid upstream item to its immutable selection reference', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json([
      { id: 'p-1', name: 'friends', version: 2, rewardAmount: 500, partyRef: 'must-not-leak' },
    ])))
    const { GET } = await import('@/app/api/referral-programs/route')
    expect(await (await GET()).json()).toEqual({ items: [{ id: 'p-1', name: 'friends', version: 2 }], state: 'ok' })
  })

  it('does not turn an unavailable service into an empty catalogue', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })))
    const { GET } = await import('@/app/api/referral-programs/route')
    expect(await (await GET()).json()).toEqual({ items: [], state: 'not_deployed' })
  })
})

describe('referral program draft creation BFF', () => {
  const VALID_BODY = {
    name: 'friends-autumn',
    version: 1,
    rewardAmount: 500,
    currency: 'CZK',
    qualifyingEvent: 'account.opened',
  }

  function postRequest(body: unknown): Request {
    return new Request('http://localhost/api/referral-programs', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    })
  }

  it('answers 401 without a session, before touching the service', async () => {
    vi.mocked(auth).mockResolvedValueOnce(null as never)
    const upstream = vi.fn()
    vi.stubGlobal('fetch', upstream)
    const { POST } = await import('@/app/api/referral-programs/route')

    const response = await POST(postRequest(VALID_BODY))
    expect(response.status).toBe(401)
    expect(await response.json()).toEqual({ error: 'unauthenticated' })
    expect(upstream).not.toHaveBeenCalled()
  })

  it('forwards only the whitelisted contract fields with the operator bearer', async () => {
    const upstream = vi.fn(async () => Response.json({ id: 'p-1', ...VALID_BODY }, { status: 201 }))
    vi.stubGlobal('fetch', upstream)
    const { POST } = await import('@/app/api/referral-programs/route')

    const response = await POST(postRequest({ ...VALID_BODY, maker: 'mallory', admin: true }))
    expect(response.status).toBe(200)
    const body = await response.json()
    expect(body.state).toBe('ok')
    expect(body.id).toBe('p-1')
    expect(upstream).toHaveBeenCalledWith('http://localhost:8155/api/v1/referrals/programs', expect.objectContaining({
      method: 'POST',
      headers: { authorization: 'Bearer token', 'content-type': 'application/json' },
      cache: 'no-store',
    }))
    const forwarded = JSON.parse((upstream.mock.calls[0][1] as RequestInit).body as string)
    expect(forwarded).toEqual(VALID_BODY)
  })

  it('passes a 409 conflict through as rejected with the service reason', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(
      { error: 'program friends-autumn@1 already exists' }, { status: 409 },
    )))
    const { POST } = await import('@/app/api/referral-programs/route')

    const response = await POST(postRequest(VALID_BODY))
    expect(response.status).toBe(200)
    expect(await response.json()).toEqual({
      state: 'rejected',
      message: 'program friends-autumn@1 already exists',
    })
  })

  it('passes a 400 validation failure through as rejected', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(
      { error: 'name must be a slug of 3-64 lowercase characters' }, { status: 400 },
    )))
    const { POST } = await import('@/app/api/referral-programs/route')

    expect(await (await POST(postRequest({ ...VALID_BODY, name: 'Bad Name' }))).json())
      .toEqual({ state: 'rejected', message: 'name must be a slug of 3-64 lowercase characters' })
  })

  it('answers unauthorized when the upstream refuses the token', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ error: 'forbidden' }, { status: 403 })))
    const { POST } = await import('@/app/api/referral-programs/route')
    expect(await (await POST(postRequest(VALID_BODY))).json()).toEqual({ state: 'unauthorized' })
  })

  it('collapses a 500 into unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 500 })))
    const { POST } = await import('@/app/api/referral-programs/route')
    expect(await (await POST(postRequest(VALID_BODY))).json()).toEqual({ state: 'unreachable' })
  })

  it('answers unreachable when the service cannot be reached at all', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('ECONNREFUSED') }))
    const { POST } = await import('@/app/api/referral-programs/route')
    expect(await (await POST(postRequest(VALID_BODY))).json()).toEqual({ state: 'unreachable' })
  })
})
