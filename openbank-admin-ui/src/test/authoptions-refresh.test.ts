// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, vi, afterEach } from 'vitest'
import type { JWT } from 'next-auth/jwt'
import type { Session } from 'next-auth'

// authOptions imports next-auth at runtime (customFetch, KeycloakProvider), which
// transitively pulls next/server — vitest can't resolve it (this is why the other
// suites mock @/auth). Stub only those runtime imports so authOptions.ts loads; the
// jwt/session callbacks and refreshAccessToken under test are still the REAL code.
vi.mock('next-auth', () => ({ customFetch: Symbol.for('customFetch') }))
vi.mock('next-auth/providers/keycloak', () => ({ default: () => ({ id: 'keycloak' }) }))

const { authOptions } = await import('@/lib/auth/authOptions')

// The end-to-end trigger side of the session self-heal (the redirect side is covered
// by reauth-on-expiry.test.ts): a failed Keycloak refresh must mark the token
// error=RefreshAccessTokenError, and the session callback must surface it as
// session.user.error — which is exactly what ReauthOnExpiry keys off.

const jwt = authOptions.callbacks!.jwt!
const session = authOptions.callbacks!.session!

const callJwt = (token: JWT) => jwt({ token, account: null, profile: undefined } as any)

// A minimal well-formed JWT so extractRoles() can decode a realm_access payload.
function fakeJwt(roles: string[]): string {
  const payload = Buffer.from(JSON.stringify({ realm_access: { roles } })).toString('base64')
  return `header.${payload}.sig`
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('authOptions — refresh failure surfaces as a session error', () => {
  it('marks the token error=RefreshAccessTokenError when the refresh grant fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      json: async () => ({ error: 'invalid_grant' }),
    }))
    const expired: JWT = {
      accessToken: 'stale', refreshToken: 'r1',
      accessTokenExpires: Date.now() - 1_000, roles: ['ROLE_ADMIN'],
    }
    const out = await callJwt(expired)
    expect(out.error).toBe('RefreshAccessTokenError')
  })

  it('does NOT refresh (or error) while the access token is still valid', async () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    const valid: JWT = { accessToken: 'ok', accessTokenExpires: Date.now() + 60_000 }
    const out = await callJwt(valid)
    expect(out.error).toBeUndefined()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('refreshes cleanly (new token, no error) when the grant succeeds', async () => {
    const rotated = fakeJwt(['ROLE_OPERATOR'])
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ access_token: rotated, expires_in: 300, refresh_token: 'r2' }),
    }))
    const expired: JWT = {
      accessToken: 'stale', refreshToken: 'r1', accessTokenExpires: Date.now() - 1_000,
    }
    const out = await callJwt(expired)
    expect(out.error).toBeUndefined()
    expect(out.accessToken).toBe(rotated)
    expect(out.roles).toEqual(['ROLE_OPERATOR'])
  })

  it('session callback propagates token.error to session.user.error', async () => {
    const token: JWT = {
      sub: 'admin', roles: ['ROLE_ADMIN'], accessToken: 'stale',
      error: 'RefreshAccessTokenError',
    }
    const out = await session({ session: { user: {} } as Session, token } as any)
    expect(out.user.error).toBe('RefreshAccessTokenError')
  })

  it('session callback leaves error undefined for a healthy token', async () => {
    const token: JWT = { sub: 'admin', roles: ['ROLE_ADMIN'], accessToken: 'fresh' }
    const out = await session({ session: { user: {} } as Session, token } as any)
    expect(out.user.error).toBeUndefined()
  })
})
