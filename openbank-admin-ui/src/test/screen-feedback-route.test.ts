// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Access and error-disclosure guards for the screen-feedback board (ADR-0192). The payload
// carries free-text comments and screenshot keys, and the route reaches ClickHouse directly —
// no downstream @RolesAllowed backstops it.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

async function route() {
  return import('@/app/api/feedback/screen-feedback/route')
}

const req = () => new NextRequest('http://localhost/api/feedback/screen-feedback')

describe('GET /api/feedback/screen-feedback', () => {
  beforeEach(() => vi.resetModules())
  afterEach(() => vi.restoreAllMocks())

  it('401s without a session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    expect((await (await route()).GET(req())).status).toBe(401)
  })

  it('403s an authenticated operator without a permitted role', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't', roles: ['ROLE_VIEWER'] } } as never)
    expect((await (await route()).GET(req())).status).toBe(403)
  })

  it('does not echo the ClickHouse error detail to the client', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't', roles: ['ROLE_COMPLIANCE'] } } as never)
    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response('Code: 60. DB::Exception: Table openbank_analytics.gold_screen_feedback does not exist', { status: 500 }),
    ))

    const body = await (await (await route()).GET(req())).json()

    expect(body.available).toBe(false)
    expect(body.error).toBe('analytics_unavailable')
    expect(JSON.stringify(body)).not.toContain('DB::Exception')
  })
})
