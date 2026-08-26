// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn(async () => ({ user: { accessToken: 'token' } })) }))
vi.mock('@/lib/services/bff', () => ({
  serverSvcUrl: (_service: string, _namespace: string, _port: number, path: string) => `http://referral${path}`,
}))

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('referral program catalogue BFF', () => {
  it('relays the operator token and preserves immutable published versions', async () => {
    const upstream = vi.fn(async () => Response.json([{ id: 'p-1', name: 'friends', version: 2, status: 'PUBLISHED' }]))
    vi.stubGlobal('fetch', upstream)
    const { GET } = await import('@/app/api/referral-programs/route')

    const response = await GET()
    expect(await response.json()).toEqual({ items: [{ id: 'p-1', name: 'friends', version: 2, status: 'PUBLISHED' }], state: 'ok' })
    expect(upstream).toHaveBeenCalledWith('http://referral/api/v1/referrals/programs', expect.objectContaining({
      headers: { authorization: 'Bearer token' }, cache: 'no-store',
    }))
  })

  it('does not turn an unavailable service into an empty catalogue', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })))
    const { GET } = await import('@/app/api/referral-programs/route')
    expect(await (await GET()).json()).toEqual({ items: [], state: 'not_deployed' })
  })
})
