// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { customFetch, type NextAuthConfig } from "next-auth"
import KeycloakProvider from "next-auth/providers/keycloak"
import type { JWT } from "next-auth/jwt"
import { extractCatalogScopeRoles } from '@/lib/auth/catalogScopes'
import { requireSecurePublicUrl } from '@/lib/auth/publicUrl'

// Public URL is what the browser and OIDC issuer metadata should see.
// Internal URL is what the NextAuth server uses for discovery/token refresh inside Docker.
// NOTE: this is SERVER-side config — read a *runtime* env var only. Do NOT reference
// NEXT_PUBLIC_* here: Next/Turbopack inlines NEXT_PUBLIC_* at build time, and when the
// var is unset during `docker build` it constant-folds the whole `||` chain down to the
// final literal ("http://localhost:8080"). That baked-in localhost then becomes
// provider.issuer, so Auth.js v5 server-side discovery compares the discovery body's
// issuer (Keycloak frontendUrl = public https) against "http://localhost:8080/realms/..."
// and fails with `"response" body "issuer" property does not match the expected value`.
// KEYCLOAK_PUBLIC_URL (no NEXT_PUBLIC_ prefix) stays a genuine runtime read.
// A secret must come from the environment. We only fall back to a well-known dev
// literal when NOT running in production; in production an unset secret is fatal
// rather than silently signing sessions / authenticating with a value that is
// public in this repository.
function requiredSecret(name: string, devFallback: string): string {
  const fromEnv = process.env[name]
  if (fromEnv && fromEnv.length > 0) return fromEnv
  // `next build` collects page data with NODE_ENV=production but BEFORE any runtime
  // secret is injected (NEXT_PHASE === "phase-production-build"). This module is
  // imported transitively by API routes (e.g. /api/domestic-payments), so a
  // module-load throw here aborts the whole build. Skip the fatal check during the
  // build phase only — the standalone server re-evaluates this module at startup
  // (NEXT_PHASE unset, NODE_ENV=production), where an unset secret IS still fatal.
  const isBuildPhase = process.env.NEXT_PHASE === "phase-production-build"
  if (process.env.NODE_ENV === "production" && !isBuildPhase) {
    throw new Error(`${name} must be set in production (refusing to fall back to a baked-in dev secret)`)
  }
  return devFallback
}

const publicUrlPolicy = {
  production: process.env.NODE_ENV === 'production',
  buildPhase: process.env.NEXT_PHASE === 'phase-production-build',
  allowInsecureLoopback: process.env.ALLOW_INSECURE_STUDIO_URLS === 'true',
}

const KEYCLOAK_PUBLIC_URL = requireSecurePublicUrl(
  'KEYCLOAK_PUBLIC_URL', process.env.KEYCLOAK_PUBLIC_URL || "http://localhost:8080", publicUrlPolicy,
)
const KEYCLOAK_INTERNAL_URL = process.env.KEYCLOAK_URL || "http://keycloak:8080"
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM || "openbank"
const CLIENT_ID      = process.env.KEYCLOAK_CLIENT_ID     || "openbank-admin-ui"
const CLIENT_SECRET  = requiredSecret("KEYCLOAK_CLIENT_SECRET", "openbank-admin-ui-secret")

const KEYCLOAK_ISSUER = `${KEYCLOAK_PUBLIC_URL}/realms/${KEYCLOAK_REALM}`
// Secure cookies whenever the portal is served over https (prod). Drives the cookie name
// prefix + the Secure flag (ADR-0080 P1 / F-AUTH-07).
const NEXTAUTH_URL = requireSecurePublicUrl('NEXTAUTH_URL', process.env.NEXTAUTH_URL ?? '', publicUrlPolicy)
const USE_SECURE_COOKIES = NEXTAUTH_URL.startsWith("https://")

// Split front/back-channel OIDC. Auth.js v5 (@auth/core) runs *server-side* OIDC
// discovery, token, userinfo and JWKS fetches against `provider.issuer` — the
// PUBLIC URL — not the legacy v4 `wellKnown` (which it ignores). In a split-horizon
// deployment the public issuer is an external https host the pod often cannot reach
// (no LB hairpin) and/or serves a cert the Node TLS layer doesn't trust; either way
// the fetch fails -> NextAuth "Configuration" error at login. @auth/core threads a
// per-provider fetch override (`customFetch`) through ALL of those server-side calls
// (discoveryRequest/token/userinfo/jwks). We use it to transparently rewrite any
// PUBLIC-host call to the in-cluster INTERNAL base (plain http Service), so the
// server never leaves the cluster and never does TLS, while the browser still uses
// the public issuer for redirects. The discovery doc returned over http still
// advertises issuer = public URL (Keycloak frontendUrl), so oauth4webapi's
// issuer check — which compares the body `issuer`, not the fetched URL — passes.
const keycloakFetch: typeof fetch = (input, init) => {
  const asUrl =
    typeof input === "string" ? input
    : input instanceof URL ? input.href
    : (input as Request).url
  if (asUrl && KEYCLOAK_PUBLIC_URL !== KEYCLOAK_INTERNAL_URL && asUrl.startsWith(KEYCLOAK_PUBLIC_URL)) {
    const rewritten = KEYCLOAK_INTERNAL_URL + asUrl.slice(KEYCLOAK_PUBLIC_URL.length)
    if (typeof input === "string" || input instanceof URL) return fetch(rewritten, init)
    // Rebuild Request objects against the internal URL, preserving method/headers/body.
    return fetch(new Request(rewritten, input as Request), init)
  }
  return fetch(input as Parameters<typeof fetch>[0], init)
}

/** Decode JWT payload without verification (roles only — not security-sensitive here) */
function decodeJwtPayload(token: string): Record<string, unknown> {
  try {
    const base64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")
    const json = Buffer.from(base64, "base64").toString("utf8")
    return JSON.parse(json)
  } catch {
    return {}
  }
}

/** Extract OpenBank realm roles plus provider-neutral catalog scopes from an OIDC JWT. */
function extractRoles(jwt: string): string[] {
  const payload = decodeJwtPayload(jwt)
  const realmAccess = payload["realm_access"] as { roles?: string[] } | undefined
  const roles = realmAccess?.roles?.filter(r => r.startsWith("ROLE_")) ?? []
  roles.push(...extractCatalogScopeRoles(payload))
  return [...new Set(roles)]
}

/**
 * Refresh a minute BEFORE the access token actually expires. A token that is valid at the
 * moment the JWT callback runs can still be expired by the time the upstream service
 * validates it, and the realm's clock is not ours.
 */
const REFRESH_SKEW_MS = 60_000

/**
 * How long a completed rotation stays answerable by the refresh token it consumed.
 * One access-token lifespan (300s) is the window in which stale copies of the cookie can
 * still arrive; 120s covers the realistic tail without holding grants longer than needed.
 */
const ROTATION_CACHE_TTL_MS = 120_000

type Rotation = Pick<JWT, "accessToken" | "accessTokenExpires" | "refreshToken" | "roles">

/**
 * Keyed by the refresh token that was SPENT, not by user.
 *
 * The realm sets `revokeRefreshToken: true` + `refreshTokenMaxReuse: 0`, so every refresh
 * token is strictly single-use. Two things then break a session that is actively in use:
 *
 *  - **Stampede.** `auth()` is called independently by the middleware, ~30 route handlers and
 *    `/api/gate` (nginx `auth_request`). Each decodes the SAME cookie, so once the access
 *    token expires they all present the same refresh token at once. One wins; every other
 *    gets `invalid_grant` and marks the session `RefreshAccessTokenError`, which the
 *    middleware turns into a redirect to `/auth/login?error=SessionExpired`.
 *  - **Rotation loss.** Only a response that reaches the browser can persist the rotated
 *    token. `/api/gate` is an nginx auth subrequest (its `Set-Cookie` is discarded) and
 *    `auth()` inside a Server Component cannot set cookies at all. Such a call spends the
 *    refresh token and throws the replacement away, leaving the browser holding a revoked
 *    one — the next refresh is then guaranteed to fail.
 *
 * Both are answered by remembering the result against the spent token: concurrent callers
 * share one in-flight grant, and a later caller still carrying the old cookie is handed the
 * already-rotated token instead of presenting a revoked one to Keycloak. The admin-ui runs a
 * single replica, so an in-process map is sufficient; a multi-replica deployment would need a
 * shared store instead.
 */
const rotationCache = new Map<string, { at: number; result: Promise<Rotation> }>()

function pruneRotationCache(now: number): void {
  for (const [key, entry] of rotationCache) {
    if (now - entry.at > ROTATION_CACHE_TTL_MS) rotationCache.delete(key)
  }
}

async function requestRotation(refreshToken: string): Promise<Rotation> {
  const url = `${KEYCLOAK_INTERNAL_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      grant_type: "refresh_token",
      refresh_token: refreshToken,
    }),
  })
  const refreshed = await res.json()
  if (!res.ok) throw refreshed
  return {
    // Re-extract roles from new access token
    roles: extractRoles(refreshed.access_token),
    accessToken: refreshed.access_token,
    accessTokenExpires: Date.now() + refreshed.expires_in * 1000,
    refreshToken: refreshed.refresh_token ?? refreshToken,
  }
}

async function refreshAccessToken(token: JWT): Promise<JWT> {
  const refreshToken = token.refreshToken as string | undefined
  if (!refreshToken) return { ...token, error: "RefreshAccessTokenError" }

  const now = Date.now()
  pruneRotationCache(now)

  let entry = rotationCache.get(refreshToken)
  if (!entry) {
    entry = { at: now, result: requestRotation(refreshToken) }
    rotationCache.set(refreshToken, entry)
    // A failed grant must not be remembered: the next request has to be free to try again
    // (Keycloak may simply have been unreachable). Attach the handler here so the shared
    // promise is never unhandled, and let the awaiting callers see the rejection too.
    entry.result.catch(() => rotationCache.delete(refreshToken))
  }

  try {
    return { ...token, ...(await entry.result), error: undefined }
  } catch {
    return { ...token, error: "RefreshAccessTokenError" }
  }
}

export const authOptions: NextAuthConfig = {
  // Self-hosted (non-Vercel): trust the deployment host instead of requiring AUTH_URL.
  trustHost: true,
  providers: [
    KeycloakProvider({
      clientId: CLIENT_ID,
      clientSecret: CLIENT_SECRET,
      issuer: KEYCLOAK_ISSUER,
      // Route every server-side OIDC fetch (discovery/token/userinfo/JWKS) to the
      // in-cluster Keycloak Service. `wellKnown` is intentionally omitted: Auth.js
      // v5 ignores it for discovery, so the rewrite below is the actual mechanism.
      [customFetch]: keycloakFetch,
    }),
  ],
  // ADR-0080 P2 (F-AUTH-03): cut the session window from 8h to 1h idle, refreshed on activity
  // (rolling). The access token inside is already short-lived and rotated (refreshAccessToken);
  // shrinking the outer session shrinks the stolen-/leaked-token replay window 8×. Full
  // server-side revocation (Keycloak backchannel logout) is the remaining follow-up.
  session: { strategy: "jwt", maxAge: 60 * 60, updateAge: 5 * 60 },
  // ADR-0080 P1 (F-AUTH-07): pin the session cookie to SameSite=Lax (was None) + HttpOnly +
  // Secure on https. Lax keeps top-level OIDC redirect logins working while removing the
  // cross-site CSRF exposure of SameSite=None. The name mirrors Auth.js v5's own default so
  // the cookie is still read; existing sessions simply re-login once.
  useSecureCookies: USE_SECURE_COOKIES,
  cookies: {
    sessionToken: {
      name: `${USE_SECURE_COOKIES ? "__Secure-" : ""}authjs.session-token`,
      options: { httpOnly: true, sameSite: "lax", path: "/", secure: USE_SECURE_COOKIES },
    },
  },
  callbacks: {
    async jwt({ token, account, profile }) {
      if (account && account.access_token) {
        // Initial sign-in: extract roles directly from access_token JWT
        // (more reliable than profile which depends on ID token mapper config)
        const roles = extractRoles(account.access_token)
        return {
          ...token,
          accessToken: account.access_token,
          refreshToken: account.refresh_token,
          // ADR-0080 P1 (F-AUTH-04): kept for federated logout — passed as id_token_hint to
          // Keycloak's end_session_endpoint so signing out of the portal ends the SSO session
          // (otherwise "Back" silently re-authenticates).
          idToken: account.id_token,
          accessTokenExpires: account.expires_at
            ? account.expires_at * 1000
            : Date.now() + 3600 * 1000,
          roles,
          // Also try profile as fallback
          ...(roles.length === 0 && profile ? (() => {
            const kc = profile as Record<string, unknown>
            const ra = kc["realm_access"] as { roles?: string[] } | undefined
            return { roles: ra?.roles?.filter(r => r.startsWith("ROLE_")) ?? [] }
          })() : {}),
        }
      }
      // Token still valid, with a margin — see REFRESH_SKEW_MS.
      if (Date.now() < (token.accessTokenExpires as number) - REFRESH_SKEW_MS) return token
      // Refresh
      return refreshAccessToken(token)
    },
    async session({ session, token }) {
      session.user = {
        ...session.user,
        id: token.sub as string,
        roles: (token.roles as string[]) ?? [],
        accessToken: token.accessToken as string,
        error: token.error as string | undefined,
      }
      return session
    },
  },
  pages: {
    signIn: "/auth/login",
    error: "/auth/error",
  },
  secret: requiredSecret("NEXTAUTH_SECRET", "openbank-admin-ui-nextauth-secret-change-in-prod"),
}
