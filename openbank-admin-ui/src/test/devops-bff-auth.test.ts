// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

describe('DevOps HITL BFF bearer relay', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } } as never)
  })
  afterEach(() => vi.restoreAllMocks())

  it('relays the bearer for a human decision', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'finding-1' }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/devops/decide/route')

    const response = await POST(new NextRequest('http://localhost/api/devops/decide', {
      method: 'POST', body: JSON.stringify({ id: 'finding-1', action: 'approve' }),
    }))

    expect(response.status).toBe(200)
    expect(new Headers(fetchMock.mock.calls[0][1].headers).get('Authorization')).toBe('Bearer operator-token')
  })

  it('does not call the agent without an operator session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/devops/decide/route')

    const response = await POST(new NextRequest('http://localhost/api/devops/decide', { method: 'POST', body: '{}' }))

    expect(response.status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
