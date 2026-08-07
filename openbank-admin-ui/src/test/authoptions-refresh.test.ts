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
    const valid: JWT = { accessToken: 'ok', accessTokenExpires: Date.now() + 300_000 }
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

  it('refreshes ahead of expiry, not after it (skew)', async () => {
    const fetchSpy = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ access_token: fakeJwt([]), expires_in: 300, refresh_token: 'skew-2' }),
    })
    vi.stubGlobal('fetch', fetchSpy)
    // Not expired yet, but inside the skew window: an upstream call made with this token
    // could still land after it expires, so it must be refreshed now.
    const nearlyExpired: JWT = {
      accessToken: 'stale', refreshToken: 'skew-1', accessTokenExpires: Date.now() + 30_000,
    }
    const out = await callJwt(nearlyExpired)
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    expect(out.refreshToken).toBe('skew-2')
  })

  // The realm sets revokeRefreshToken + refreshTokenMaxReuse: 0, so a refresh token is
  // single-use. Both tests below fail against a plain per-call refresh: the second grant
  // gets invalid_grant and the session is marked expired while the operator is active.

  it('collapses concurrent refreshes of the same token into ONE grant', async () => {
    const rotated = fakeJwt(['ROLE_ADMIN'])
    let grants = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => {
      grants += 1
      // Only the FIRST presentation of a single-use refresh token can succeed.
      if (grants > 1) return { ok: false, json: async () => ({ error: 'invalid_grant' }) }
      return {
        ok: true,
        json: async () => ({ access_token: rotated, expires_in: 300, refresh_token: 'conc-2' }),
      }
    }))
    const expired = (): JWT => ({
      accessToken: 'stale', refreshToken: 'conc-1', accessTokenExpires: Date.now() - 1_000,
    })
    // The middleware, a route handler and /api/gate all decode the same cookie at once.
    const outs = await Promise.all([callJwt(expired()), callJwt(expired()), callJwt(expired())])
    expect(grants).toBe(1)
    for (const out of outs) {
      expect(out.error).toBeUndefined()
      expect(out.refreshToken).toBe('conc-2')
    }
  })

  it('answers a caller still holding the SPENT token with the rotated one', async () => {
    const rotated = fakeJwt(['ROLE_ADMIN'])
    let grants = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => {
      grants += 1
      if (grants > 1) return { ok: false, json: async () => ({ error: 'invalid_grant' }) }
      return {
        ok: true,
        json: async () => ({ access_token: rotated, expires_in: 300, refresh_token: 'lost-2' }),
      }
    }))
    const expired = (): JWT => ({
      accessToken: 'stale', refreshToken: 'lost-1', accessTokenExpires: Date.now() - 1_000,
    })
    // First caller is an nginx auth subrequest / Server Component: it spends the token but
    // its Set-Cookie never reaches the browser, so the next request still carries 'lost-1'.
    await callJwt(expired())
    const second = await callJwt(expired())
    expect(grants).toBe(1)
    expect(second.error).toBeUndefined()
    expect(second.accessToken).toBe(rotated)
    expect(second.refreshToken).toBe('lost-2')
  })

  it('does not remember a FAILED grant — the next request retries', async () => {
    let grants = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => {
      grants += 1
      // Keycloak briefly unreachable, then healthy.
      if (grants === 1) return { ok: false, json: async () => ({ error: 'temporarily_unavailable' }) }
      return {
        ok: true,
        json: async () => ({ access_token: fakeJwt([]), expires_in: 300, refresh_token: 'retry-2' }),
      }
    }))
    const expired = (): JWT => ({
      accessToken: 'stale', refreshToken: 'retry-1', accessTokenExpires: Date.now() - 1_000,
    })
    expect((await callJwt(expired())).error).toBe('RefreshAccessTokenError')
    const second = await callJwt(expired())
    expect(grants).toBe(2)
    expect(second.error).toBeUndefined()
  })

  it('clears a previous RefreshAccessTokenError once a refresh succeeds', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ access_token: fakeJwt([]), expires_in: 300, refresh_token: 'heal-2' }),
    }))
    const out = await callJwt({
      accessToken: 'stale', refreshToken: 'heal-1',
      accessTokenExpires: Date.now() - 1_000, error: 'RefreshAccessTokenError',
    })
    expect(out.error).toBeUndefined()
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
