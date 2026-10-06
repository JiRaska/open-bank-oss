// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'
import { GET } from '@/app/api/referral-programs/[id]/funnel/route'

const id = '61fba07a-514c-43c9-a42f-ec99ac1269a5'
const context = { params: Promise.resolve({ id }) }

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('referral lifecycle funnel BFF', () => {
  it('refuses an authenticated user without a reporting role', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't', roles: ['ROLE_VIEWER'] } } as never)
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    expect((await GET(new Request(`http://localhost/api/referral-programs/${id}/funnel`), context)).status).toBe(403)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('returns only aggregate lifecycle fields and keeps legacy version unknown', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't', roles: ['ROLE_OPERATOR'] } } as never)
    const fetcher = vi.fn(async (_url: unknown, _options?: RequestInit) => Response.json({
      data: [{ program_version: null, qualified_events: '3', reward_requested_events: 2,
        accepted_outcomes: 0, rejected_outcomes: 1, reversed_outcomes: 0,
        last_observed_at: '2026-09-01 12:00:00.000',
        referrerPartyId: 'private-person', rawPayload: 'private-event' }],
    }))
    vi.stubGlobal('fetch', fetcher)

    const response = await GET(new Request(`http://localhost/api/referral-programs/${id}/funnel`), context)
    const body = await response.json()
    expect(body.state).toBe('ok')
    expect(body.items).toEqual([{ program_version: null, qualified_events: 3,
      reward_requested_events: 2, accepted_outcomes: 0, rejected_outcomes: 1, reversed_outcomes: 0,
      last_observed_at: '2026-09-01 12:00:00.000' }])
    expect(body.ingestionFreshness).toBe('unknown')
    expect(JSON.stringify(body)).not.toContain('private-person')
    const query = String(fetcher.mock.calls[0][1]?.body)
    expect(query).toContain('gold_referral_lifecycle_funnel')
    expect(query).not.toContain('bronze_events')
  })

  it('marks an empty result as freshness unknown', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't', roles: ['ROLE_AUDITOR'] } } as never)
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ data: [] })))
    const body = await (await GET(new Request(`http://localhost/api/referral-programs/${id}/funnel`), context)).json()
    expect(body).toMatchObject({ items: [], ingestionFreshness: 'unknown' })
  })
})
