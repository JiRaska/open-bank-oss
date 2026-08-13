// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: async () => ({ user: { accessToken: 'token' } }) }))
vi.mock('@/lib/services/bff', () => ({ serverSvcUrl: (_service: string, _name: string, _port: number, path: string) => `http://campaign${path}` }))

describe('campaign entry catalogue BFF', () => {
  beforeEach(() => vi.resetModules())

  it('forwards entry and cross-channel content catalogues through the authenticated BFF', async () => {
    const fetchMock = vi.fn(async (url: string) => ({
      ok: true,
      status: 200,
      json: async () => url.endsWith('/cadences')
        ? [{ cadence: 'DAILY_MORNING', humanForm: 'every day at 09:00', zone: 'Europe/Prague' }]
        : url.endsWith('/templates')
          ? [{ template: 'MARKETING_PRODUCT_OFFER_BANNER', channel: 'BANNER', variables: ['offerTitle'], inAppSurface: 'HOME_BANNER' }]
          : [{ trigger: 'ACCOUNT_OPENED', humanForm: 'when an account is opened' }],
    }))
    vi.stubGlobal('fetch', fetchMock)

    const { GET: cadences } = await import('@/app/api/campaigns/cadences/route')
    const { GET: triggers } = await import('@/app/api/campaigns/triggers/route')
    const { GET: templates } = await import('@/app/api/campaigns/templates/route')

    await expect((await cadences()).json()).resolves.toEqual({
      items: [{ cadence: 'DAILY_MORNING', humanForm: 'every day at 09:00', zone: 'Europe/Prague' }],
      state: 'ok',
    })
    await expect((await triggers()).json()).resolves.toEqual({
      items: [{ trigger: 'ACCOUNT_OPENED', humanForm: 'when an account is opened' }],
      state: 'ok',
    })
    await expect((await templates()).json()).resolves.toEqual({
      items: [{ template: 'MARKETING_PRODUCT_OFFER_BANNER', channel: 'BANNER', variables: ['offerTitle'], inAppSurface: 'HOME_BANNER' }],
      state: 'ok',
    })
    expect(fetchMock).toHaveBeenCalledWith(
      'http://campaign/api/v1/campaigns/cadences',
      expect.objectContaining({ headers: { authorization: 'Bearer token' } }),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      'http://campaign/api/v1/campaigns/triggers',
      expect.objectContaining({ headers: { authorization: 'Bearer token' } }),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      'http://campaign/api/v1/campaigns/templates',
      expect.objectContaining({ headers: { authorization: 'Bearer token' } }),
    )
  })
})
