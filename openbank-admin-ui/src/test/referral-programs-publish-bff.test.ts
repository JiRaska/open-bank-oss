// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { auth } from '@/auth'

vi.mock('@/auth', () => ({ auth: vi.fn(async () => ({ user: { accessToken: 'token' } })) }))
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const PROGRAM_ID = '7b1f1d5e-0d2a-4a6a-8f7e-2c1b9a0d3e4f'

function publishParams(id: string) {
  return { params: Promise.resolve({ id }) }
}

describe('referral program publish BFF', () => {
  it('answers 401 without a session, before touching the service', async () => {
    vi.mocked(auth).mockResolvedValueOnce(null as never)
    const upstream = vi.fn()
    vi.stubGlobal('fetch', upstream)
    const { POST } = await import('@/app/api/referral-programs/[id]/publish/route')

    const response = await POST(new Request('http://localhost/api/referral-programs/x/publish', { method: 'POST' }), publishParams(PROGRAM_ID))
    expect(response.status).toBe(401)
    expect(await response.json()).toEqual({ error: 'unauthenticated' })
    expect(upstream).not.toHaveBeenCalled()
  })

  it('rejects a malformed program id with 400 without calling the service', async () => {
    const upstream = vi.fn()
    vi.stubGlobal('fetch', upstream)
    const { POST } = await import('@/app/api/referral-programs/[id]/publish/route')

    const response = await POST(new Request('http://localhost/api/referral-programs/not-a-uuid/publish', { method: 'POST' }), publishParams('not-a-uuid'))
    expect(response.status).toBe(400)
    expect(upstream).not.toHaveBeenCalled()
  })

  it('forwards the publish with the operator bearer and passes the published program through', async () => {
    const upstream = vi.fn(async () => Response.json({ id: PROGRAM_ID, status: 'PUBLISHED' }))
    vi.stubGlobal('fetch', upstream)
    const { POST } = await import('@/app/api/referral-programs/[id]/publish/route')

    const response = await POST(new Request('http://localhost/api/referral-programs/x/publish', { method: 'POST' }), publishParams(PROGRAM_ID))
    expect(response.status).toBe(200)
    expect(await response.json()).toEqual({ state: 'ok', id: PROGRAM_ID, status: 'PUBLISHED' })
    expect(upstream).toHaveBeenCalledWith(
      `http://localhost:8155/api/v1/referrals/programs/${PROGRAM_ID}/publish`,
      expect.objectContaining({ method: 'POST', headers: { authorization: 'Bearer token' }, cache: 'no-store' }),
    )
  })

  it('passes the maker-checker 409 through as rejected with the service reason', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(
      { error: 'maker cannot publish their own program' }, { status: 409 },
    )))
    const { POST } = await import('@/app/api/referral-programs/[id]/publish/route')

    expect(await (await POST(new Request('http://localhost/api/referral-programs/x/publish', { method: 'POST' }), publishParams(PROGRAM_ID))).json())
      .toEqual({ state: 'rejected', message: 'maker cannot publish their own program' })
  })

  it('answers unauthorized when the upstream refuses the token', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 403 })))
    const { POST } = await import('@/app/api/referral-programs/[id]/publish/route')
    expect(await (await POST(new Request('http://localhost/api/referral-programs/x/publish', { method: 'POST' }), publishParams(PROGRAM_ID))).json())
      .toEqual({ state: 'unauthorized' })
  })

  it('collapses an upstream failure into unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('ECONNREFUSED') }))
    const { POST } = await import('@/app/api/referral-programs/[id]/publish/route')
    expect(await (await POST(new Request('http://localhost/api/referral-programs/x/publish', { method: 'POST' }), publishParams(PROGRAM_ID))).json())
      .toEqual({ state: 'unreachable' })
  })
})
