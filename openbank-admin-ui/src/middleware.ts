// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from "next/server"
import { auth } from "@/auth"

// ADR-0080 P1 (F-AUTH-06): per-request CSP with a nonce + 'strict-dynamic' instead of
// 'unsafe-inline' on script-src. A static next.config header can't carry a fresh nonce, so the
// CSP is built here. The app has no inline <script>/dangerouslySetInnerHTML; only Next's own
// bootstrap scripts run, and they pick up the nonce automatically from this header.
const KC_URL = process.env.NEXT_PUBLIC_KEYCLOAK_URL || "https://kc.open-bank.tech"
// GlitchTip crash/error ingest origin (ADR-0075). The browser SDK POSTs events here,
// so it must be allowed in connect-src or the CSP blocks every error report. Derived
// from the DSN host; only the in-cluster GlitchTip, never sentry.io.
const GLITCHTIP_ORIGIN = (() => {
  try { return new URL(process.env.NEXT_PUBLIC_GLITCHTIP_DSN || "https://glitchtip.open-bank.tech").origin }
  catch { return "https://glitchtip.open-bank.tech" }
})()

function buildCsp(nonce: string): string {
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'`,
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data: blob:",
    `connect-src 'self' ${KC_URL} ${GLITCHTIP_ORIGIN}`,
    "frame-src 'self'",
    "frame-ancestors 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join("; ")
}

export default auth((req) => {
  const session = req.auth
  const pathname = req.nextUrl.pathname
  const nonce = btoa(crypto.randomUUID())
  const csp = buildCsp(nonce)
  const withCsp = (res: NextResponse): NextResponse => {
    res.headers.set("Content-Security-Policy", csp)
    return res
  }
  // Pass the nonce to the request so Next.js stamps it on its bootstrap scripts.
  const nextWithNonce = (): NextResponse => {
    const requestHeaders = new Headers(req.headers)
    requestHeaders.set("x-nonce", nonce)
    requestHeaders.set("Content-Security-Policy", csp)
    return withCsp(NextResponse.next({ request: { headers: requestHeaders } }))
  }

  // The /auth/* pages (login, error, forbidden) are public — apply the nonce CSP but DON'T run
  // the auth gate (it would loop /auth/login → /auth/login). They are matched by middleware only
  // so the CSP reaches them (ADR-0080 P1: CSP must cover the pre-auth login page too).
  if (pathname.startsWith("/auth")) {
    return nextWithNonce()
  }

  // Not authenticated → send to login.
  if (!session?.user) {
    const loginUrl = new URL("/auth/login", req.url)
    loginUrl.searchParams.set("callbackUrl", pathname)
    return withCsp(NextResponse.redirect(loginUrl))
  }

  // Token error (e.g. refresh failed) → force re-login.
  if (session.user.error === "RefreshAccessTokenError") {
    const loginUrl = new URL("/auth/login", req.url)
    loginUrl.searchParams.set("error", "SessionExpired")
    return withCsp(NextResponse.redirect(loginUrl))
  }

  const roles: string[] = session.user.roles ?? []

  // Role-based route guards
  const routeGuards: { pattern: RegExp; required: string[] }[] = [
    { pattern: /^\/audit/,              required: ["ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_COMPLIANCE"] },
    { pattern: /^\/kyc/,               required: ["ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_COMPLIANCE", "ROLE_KYC", "ROLE_KYC_OPENER", "ROLE_KYC_REVIEWER"] },
    { pattern: /^\/regulatory/,        required: ["ROLE_ADMIN", "ROLE_COMPLIANCE"] },
    // Screen feedback carries free-text comments and screenshot keys — personal data (ADR-0192).
    { pattern: /^\/feedback/,          required: ["ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_COMPLIANCE"] },
    { pattern: /^\/system\/config/,    required: ["ROLE_ADMIN"] },
    { pattern: /^\/payments/,          required: ["ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_PAYMENTS", "ROLE_SUPERVISOR"] },
    { pattern: /^\/parties/,           required: ["ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_COMPLIANCE", "ROLE_KYC", "ROLE_KYC_OPENER", "ROLE_KYC_REVIEWER"] },
  ]

  for (const guard of routeGuards) {
    if (guard.pattern.test(pathname)) {
      const allowed = roles.some(r => guard.required.includes(r))
      if (!allowed) {
        const forbidden = new URL("/auth/forbidden", req.url)
        forbidden.searchParams.set("path", pathname)
        return withCsp(NextResponse.redirect(forbidden))
      }
    }
  }

  return nextWithNonce()
})

// Protect all app routes except auth pages and Next.js internals.
// NOTE: api/agent/mcp is intentionally NOT excluded — it is an authenticated
// operator endpoint (a JSON-RPC relay to agent-service), gated by the session
// middleware exactly like its sibling api/agent/chat. External MCP clients must
// reach agent-service directly under its own OPA policy (ADR-0031/0034), not via
// this browser-origin BFF.
export const config = {
  matcher: [
    // SECURITY (ADR-0080 P0 + post-pentest review): internal/business endpoints must NOT be
    // public. The original exclusion left api/security (full security-scan report), api/test-results
    // (service test inventory), api/catalog/health (service map), api/product-catalog and api/fx /
    // api/sanctions reachable WITHOUT a token — the same unauthenticated-info-disclosure class as
    // F-AUTH-01/02. They are consumed only by gated pages (security, system/tests, product-catalog,
    // fees, devops), so the session cookie still reaches them; nothing pre-auth needs them. Now the
    // ONLY exclusions are Auth.js's own handlers and Next static assets — everything else is gated.
    // (k8s probes the pod via tcpSocket, not an /api path, so gating /api can't break liveness.)
    // NOTE: /auth/* is intentionally NOT excluded (ADR-0080 P1): the middleware must run there to
    // emit the nonce CSP on the pre-auth login page. The callback short-circuits /auth so the auth
    // gate doesn't loop. api/auth stays excluded (Auth.js owns its own handlers; JSON, no CSP need).
    "/((?!api/auth|_next/static|_next/image|favicon.ico|robots.txt).*)",
  ],
}
