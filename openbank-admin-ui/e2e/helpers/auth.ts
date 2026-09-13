// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// E2E sign-in helper. There is no Keycloak instance in the Playwright environment
// (webServer.env in playwright.config.ts only sets NEXTAUTH_URL/NEXTAUTH_SECRET, not
// KEYCLOAK_*), so a real OIDC login is impossible here. src/proxy.ts gates every
// non-auth route on a valid Auth.js session cookie with no test bypass in the
// middleware/authOptions themselves (by design — auth bypasses don't belong in
// production code paths, ADR-0080). Instead, mint a session-token cookie the same way
// Auth.js itself would after a real login: encode a JWT with the exact secret/salt
// authOptions.ts uses (@auth/core/jwt, salt = the cookie name) and inject it via
// context.addCookies(). The middleware decodes it with the identical secret and
// accepts it as a genuine session — no production code touched.
import { encode } from '@auth/core/jwt'
import type { BrowserContext } from '@playwright/test'

// Reads the same env var playwright.config.ts injects into the dev server
// (webServer.env.NEXTAUTH_SECRET), falling back to its default — one literal instead of
// two copies that can silently drift apart. This secret only ever signs cookies for the
// ephemeral `next dev` server Playwright spawns for this test run; it is never a
// production value and authOptions.ts refuses this exact fallback outside NODE_ENV=production
// (requiredSecret()), so there is no fail-fast to add here — a missing env var here just
// means "use the same harmless test default", not a misconfigured deployment.
const NEXTAUTH_SECRET = process.env.NEXTAUTH_SECRET ?? 'e2e-test-secret'
// Must match authOptions.ts cookies.sessionToken.name: NEXTAUTH_URL is http://, so
// USE_SECURE_COOKIES is false and the cookie has no `__Secure-` prefix.
const SESSION_COOKIE_NAME = 'authjs.session-token'

/** Signs a browser context in with an explicit role set, bypassing Keycloak. */
export async function signInWithRoles(context: BrowserContext, baseURL: string, roles: string[]): Promise<void> {
  const token = await encode({
    secret: NEXTAUTH_SECRET,
    salt: SESSION_COOKIE_NAME,
    token: {
      sub: 'e2e-operator',
      name: 'E2E Operator',
      email: 'e2e-operator@openbank.test',
      roles,
      accessToken: 'e2e-fake-access-token',
      accessTokenExpires: Date.now() + 60 * 60 * 1000,
    },
  })
  await context.addCookies([{ name: SESSION_COOKIE_NAME, value: token, url: baseURL }])
}

/** Signs the given browser context in as an operator with every role, bypassing Keycloak. */
export async function signInAsOperator(context: BrowserContext, baseURL: string): Promise<void> {
  return signInWithRoles(
    context,
    baseURL,
    ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_AUDITOR', 'ROLE_COMPLIANCE', 'ROLE_PAYMENTS'],
  )
}
