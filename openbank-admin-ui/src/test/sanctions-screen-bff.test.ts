// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The manual screen must use /api/v3/sanctions/screen: the deprecated v1 endpoint refuses the
// CZ_TERRORISTS list with 400, so selecting the Czech national list in the screen form failed.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }

describe('sanctions screen BFF', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('posts a CZ_TERRORISTS screen to the v3 endpoint with the body untouched', async () => {
    global.fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: 'check-1', status: 'CLEAR' }), {
        status: 201, headers: { 'Content-Type': 'application/json' },
      }),
    )
    const { POST } = await import('../app/api/sanctions/screen/route')
    const payload = { idempotencyKey: 'k-1', entityType: 'INDIVIDUAL', name: 'Test Person', listTypes: ['CZ_TERRORISTS'] }

    const res = await POST(new NextRequest('http://localhost/api/sanctions/screen', {
      method: 'POST', body: JSON.stringify(payload),
    }))

    expect(res.status).toBe(201)
    const [url, init] = vi.mocked(global.fetch).mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8123/api/v3/sanctions/screen')
    expect(JSON.parse(init.body as string)).toEqual(payload)
  })
})
