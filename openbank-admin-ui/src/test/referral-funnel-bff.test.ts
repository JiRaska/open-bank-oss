// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn(async () => ({ user: { accessToken: 'token' } })) }))

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('referral funnel BFF', () => {
  it('maps the governed gold projection without inventing missing counts', async () => {
    const clickhouse = vi.fn(async (_url: string, init?: RequestInit) => Response.json({ data: [{
      program_id: 'p-1', qualified_invites: '4', reward_requests: '3', rewarded_invites: '2',
      failed_rewards: '1', reversed_rewards: '0', requested_reward_amount: '1500', currency: 'CZK',
      first_observed_at: '2026-08-01 10:00:00.000', last_observed_at: '2026-08-02 10:00:00.000',
    }] }))
    vi.stubGlobal('fetch', clickhouse)
    const { GET } = await import('@/app/api/referral-programs/funnel/route')

    expect(await (await GET()).json()).toEqual({ state: 'ok', items: [expect.objectContaining({
      programId: 'p-1', qualifiedInvites: 4, rewardRequests: 3, rewardedInvites: 2,
      failedRewards: 1, reversedRewards: 0, requestedRewardAmount: 1500, currency: 'CZK',
    })] })
    expect(String(clickhouse.mock.calls[0]?.[1]?.body)).toContain('gold_referral_funnel')
  })

  it('reports unavailable instead of a false zero funnel', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 503 })))
    const { GET } = await import('@/app/api/referral-programs/funnel/route')
    expect(await (await GET()).json()).toEqual({ items: [], state: 'unavailable' })
  })
})
