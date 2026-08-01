// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from "next/server"
import { auth } from "@/auth"
import { hasPermission, type Permission } from "@/lib/auth/roles"

export const dynamic = "force-dynamic"

/**
 * ADR-0234 — identity-aware edge gate for internal tool UIs.
 *
 * nginx calls this as an `auth_request` sub-request (annotation
 * `nginx.ingress.kubernetes.io/auth-url`) BEFORE it proxies a `/tools/<tool>/…`
 * request to the tool. The browser's cookies ride along on the sub-request, so
 * this route sees the same `__Secure-authjs.session-token` the console uses —
 * which is why the tools must live under `admin.open-bank.tech` and not on a
 * hostname of their own (the cookie is host-only; see the ADR).
 *
 * Contract with nginx — deliberately narrow:
 *   204  → allow, proxy the request
 *   401  → deny; `auth-signin` turns this into a redirect to the console login
 *   403  → authenticated but not entitled to THIS tool
 * Anything else nginx maps to a 500, so this route must never redirect and must
 * never throw. It returns no body on success and proxies nothing: its whole job
 * is to answer "may this session reach this tool".
 *
 * This is the one route excluded from `src/middleware.ts` (ADR-0080 P0 kept the
 * matcher at "everything except Auth.js and static assets"). It has to be: the
 * middleware answers an unauthenticated request with a 302 to /auth/login, and
 * nginx turns any non-2xx/401/403 auth sub-response into a 500 — so the gate
 * would fail closed on exactly the case it exists to handle. The session check
 * below is the same one the middleware would have applied.
 */

/**
 * Tools reachable through the gate, and the permission each one requires.
 *
 * An ALLOW-list keyed by the `?tool=` the Ingress hard-codes into its auth-url —
 * an unknown tool is denied, so adding an Ingress path without adding it here
 * fails closed rather than open.
 *
 * The permission is the SAME one the Sidebar uses to decide whether to render
 * the link, deliberately: a gate wider than the nav hides access nobody can
 * find, a gate narrower than the nav renders a link that 403s. Expressing both
 * as `system:view` means widening access is one edit in `roles.ts`, not two
 * edits kept in sync.
 *
 * Grafana's own `role_attribute_path` (kube-prometheus-stack values) then maps
 * ROLE_ADMIN→Admin and ROLE_OPERATOR→Editor. The gate is the coarse "may you
 * reach it at all" layer; the tool's own SSO decides what you can do inside.
 */
const TOOL_PERMISSIONS: Record<string, Permission> = {
  grafana: "system:view",
  alertmanager: "system:view",
  pyrra: "system:view",
}

export async function GET(req: NextRequest): Promise<NextResponse> {
  const tool = req.nextUrl.searchParams.get("tool") ?? ""
  const required = TOOL_PERMISSIONS[tool]

  // Unknown or missing tool: deny. A 403 (not 401) — re-authenticating cannot help,
  // and a 401 here would bounce the operator through a login loop.
  if (!required) {
    return new NextResponse(null, { status: 403, headers: { "Cache-Control": "no-store" } })
  }

  // `auth()` is typed as an overloaded helper, so its awaited type is not the
  // Session — read the user off it inside the try rather than annotating it.
  let user: { roles?: string[]; error?: string } | undefined
  try {
    user = (await auth())?.user
  } catch {
    // Fail closed. A thrown session decode is not an allow.
    return new NextResponse(null, { status: 401, headers: { "Cache-Control": "no-store" } })
  }

  // No session, or a session whose Keycloak refresh has failed (the middleware
  // forces a re-login on the same condition).
  if (!user || user.error === "RefreshAccessTokenError") {
    return new NextResponse(null, { status: 401, headers: { "Cache-Control": "no-store" } })
  }

  const roles: string[] = user.roles ?? []
  if (!hasPermission(roles, required)) {
    return new NextResponse(null, { status: 403, headers: { "Cache-Control": "no-store" } })
  }

  return new NextResponse(null, { status: 204, headers: { "Cache-Control": "no-store" } })
}
