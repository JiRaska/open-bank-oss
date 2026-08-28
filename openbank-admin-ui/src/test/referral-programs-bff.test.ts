// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn(async () => ({ user: { accessToken: 'token' } })) }))
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('referral program catalogue BFF', () => {
  it('relays the operator token and preserves immutable published versions', async () => {
    const upstream = vi.fn(async () => Response.json({ items: [{ id: 'p-1', name: 'friends', version: 2 }] }))
    vi.stubGlobal('fetch', upstream)
    const { GET } = await import('@/app/api/referral-programs/route')

    const response = await GET()
    expect(await response.json()).toEqual({ items: [{ id: 'p-1', name: 'friends', version: 2 }], state: 'ok' })
    expect(upstream).toHaveBeenCalledWith('http://localhost:8155/api/v1/referrals/programs', expect.objectContaining({
      headers: { authorization: 'Bearer token' }, cache: 'no-store',
    }))
  })

  it('does not reinterpret a malformed upstream catalogue as an empty published one', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ items: [{ id: 'p-1', name: 'friends' }] })))
    const { GET } = await import('@/app/api/referral-programs/route')
    expect(await (await GET()).json()).toEqual({ items: [], state: 'unreachable' })
  })

  it('projects a valid upstream item to its immutable selection reference', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({
      items: [{ id: 'p-1', name: 'friends', version: 2, rewardAmount: 500, partyRef: 'must-not-leak' }],
    })))
    const { GET } = await import('@/app/api/referral-programs/route')
    expect(await (await GET()).json()).toEqual({ items: [{ id: 'p-1', name: 'friends', version: 2 }], state: 'ok' })
  })

  it('does not turn an unavailable service into an empty catalogue', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })))
    const { GET } = await import('@/app/api/referral-programs/route')
    expect(await (await GET()).json()).toEqual({ items: [], state: 'not_deployed' })
  })
})
