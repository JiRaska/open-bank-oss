// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { customFetch, type NextAuthConfig } from "next-auth"
import KeycloakProvider from "next-auth/providers/keycloak"
import type { JWT } from "next-auth/jwt"

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

const KEYCLOAK_PUBLIC_URL   = process.env.KEYCLOAK_PUBLIC_URL || "http://localhost:8080"
const KEYCLOAK_INTERNAL_URL = process.env.KEYCLOAK_URL || "http://keycloak:8080"
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM || "openbank"
const CLIENT_ID      = process.env.KEYCLOAK_CLIENT_ID     || "openbank-admin-ui"
const CLIENT_SECRET  = requiredSecret("KEYCLOAK_CLIENT_SECRET", "openbank-admin-ui-secret")

const KEYCLOAK_ISSUER = `${KEYCLOAK_PUBLIC_URL}/realms/${KEYCLOAK_REALM}`
// Secure cookies whenever the portal is served over https (prod). Drives the cookie name
// prefix + the Secure flag (ADR-0080 P1 / F-AUTH-07).
const USE_SECURE_COOKIES = (process.env.NEXTAUTH_URL ?? "").startsWith("https://")

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

/** Extract ROLE_* roles from any Keycloak JWT */
function extractRoles(jwt: string): string[] {
  const payload = decodeJwtPayload(jwt)
  const realmAccess = payload["realm_access"] as { roles?: string[] } | undefined
  return realmAccess?.roles?.filter(r => r.startsWith("ROLE_")) ?? []
}

async function refreshAccessToken(token: JWT): Promise<JWT> {
  try {
    const url = `${KEYCLOAK_INTERNAL_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: CLIENT_ID,
        client_secret: CLIENT_SECRET,
        grant_type: "refresh_token",
        refresh_token: token.refreshToken as string,
      }),
    })
    const refreshed = await res.json()
    if (!res.ok) throw refreshed
    // Re-extract roles from new access token
    const roles = extractRoles(refreshed.access_token)
    return {
      ...token,
      accessToken: refreshed.access_token,
      accessTokenExpires: Date.now() + refreshed.expires_in * 1000,
      refreshToken: refreshed.refresh_token ?? token.refreshToken,
      roles,
    }
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
      // Token still valid
      if (Date.now() < (token.accessTokenExpires as number)) return token
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
