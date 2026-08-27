// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from "next/server"
import { auth } from "@/auth"
import { hasPermission, permissionForPath } from "@/lib/auth/roles"

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
    "style-src 'self' 'unsafe-inline'",
    "font-src 'self'",
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

  // The /auth/* pages (login, error, forbidden), the GDPR privacy notice, and
  // RFC 9116 security.txt are public — apply the nonce CSP but DON'T run the
  // auth gate (it would loop /auth/login → /auth/login, or make the incident
  // contact unreachable without an account). Matched by middleware only so the
  // CSP still reaches them (ADR-0080 P1: CSP must cover pre-auth pages too).
  if (pathname.startsWith("/auth") || pathname === "/privacy" || pathname.startsWith("/.well-known/")) {
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

  // ADR-0229 D3: one permission projection covers every authenticated console route instead
  // of seven hand-maintained role lists. The route map shares the UI permission matrix, so nav,
  // deep links and the initial response cannot disagree about a destination's visibility.
  const permission = permissionForPath(pathname)
  if (permission && !hasPermission(roles, permission)) {
    const forbidden = new URL("/auth/forbidden", req.url)
    forbidden.searchParams.set("path", pathname)
    return withCsp(NextResponse.redirect(forbidden))
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
    // ONLY exclusions are Auth.js's own handlers, Next static assets, and the public brand assets
    // needed by the pre-auth login page — everything else is gated.
    // (k8s probes the pod via tcpSocket, not an /api path, so gating /api can't break liveness.)
    // NOTE: /auth/* is intentionally NOT excluded (ADR-0080 P1): the middleware must run there to
    // emit the nonce CSP on the pre-auth login page. The callback short-circuits /auth so the auth
    // gate doesn't loop. api/auth stays excluded (Auth.js owns its own handlers; JSON, no CSP need).
    // api/gate is excluded for a mechanical reason, NOT as a relaxation (ADR-0234): it is nginx's
    // `auth_request` target for the /tools/* Ingress, and nginx maps any auth sub-response that is
    // not 2xx/401/403 to a 500 — so the middleware's 302-to-login would make the gate fail closed on
    // precisely the unauthenticated request it exists to reject cleanly. The route runs the same
    // session + role check itself, returns 204/401/403 with no body, and proxies nothing.
    "/((?!api/auth|api/gate|_next/static|_next/image|brand/|favicon.ico|robots.txt).*)",
  ],
}
