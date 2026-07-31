// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Integration tests for the entity-resolution facade (/api/entities/resolve, ADR-0228 D2) —
// mocked party/account providers + session.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({
  auth: vi.fn(),
}))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }
const IBAN = 'CZ6508000000192000145399'

function req(q: string): NextRequest {
  return new NextRequest(`http://localhost/api/entities/resolve?q=${encodeURIComponent(q)}`)
}

async function route(): Promise<typeof import('@/app/api/entities/resolve/route')> {
  return import('@/app/api/entities/resolve/route')
}

describe('entity-resolution facade (ADR-0228 D2)', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('401s without an operator session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const res = await (await route()).GET(req('novak'))
    expect(res.status).toBe(401)
  })

  it('returns an empty result set for a sub-2-char term without calling any provider', async () => {
    const mock = vi.fn()
    vi.stubGlobal('fetch', mock)
    const res = await (await route()).GET(req('a'))
    expect(res.status).toBe(200)
    expect((await res.json()).results).toEqual([])
    expect(mock).not.toHaveBeenCalled()
  })

  it('fans out to the party provider and maps typed refs with deep-link routes', async () => {
    const mock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('parties/search')) {
        return Promise.resolve(new Response(JSON.stringify({
          data: [{ id: 'p-1', legalName: 'Jan Novák', partyType: 'INDIVIDUAL', status: 'ACTIVE' }],
          pagination: { hasNextPage: false },
        }), { status: 200 }))
      }
      return Promise.resolve(new Response('not found', { status: 404 }))
    })
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).GET(req('novak'))
    const body = await res.json()
    expect(body.results).toEqual([
      { type: 'party', id: 'p-1', label: 'Jan Novák', sublabel: 'INDIVIDUAL · ACTIVE', route: '/parties/p-1' },
    ])
    // The operator's bearer is relayed to the provider, never minted fresh.
    const [, init] = mock.mock.calls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>).authorization).toBe('Bearer operator-token')
  })

  it('adds an account ref when the term is a valid IBAN', async () => {
    const mock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/accounts/iban/')) {
        return Promise.resolve(new Response(JSON.stringify({
          id: 'a-9', accountNumber: '192000145399/0800', currencyCode: 'CZK', status: 'ACTIVE',
        }), { status: 200 }))
      }
      return Promise.resolve(new Response(JSON.stringify({ data: [] }), { status: 200 }))
    })
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).GET(req(IBAN))
    const body = await res.json()
    expect(body.results).toContainEqual({
      type: 'account', id: 'a-9', label: '192000145399/0800', sublabel: 'CZK · ACTIVE', route: '/accounts/a-9',
    })
  })

  it('degrades to the working half when one provider is down', async () => {
    const mock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('parties/search')) return Promise.reject(new Error('party-service down'))
      return Promise.resolve(new Response(JSON.stringify({ data: [] }), { status: 200 }))
    })
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).GET(req('novak'))
    expect(res.status).toBe(200)
    expect((await res.json()).results).toEqual([])
  })
})
