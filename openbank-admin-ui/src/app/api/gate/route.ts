// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from "next/server"
import { decideToolAccess } from "@/lib/auth/toolGate"

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
 * This file is the nginx contract; `./[tool]/[[...uri]]/route.ts` is the Envoy Gateway
 * one (ADR-0324 Phase 2). Both answer the same decision (`@/lib/auth/toolGate`).
 *
 * Contract with nginx — deliberately narrow:
 *   204  → allow, proxy the request
 *   401  → deny; `auth-signin` turns this into a redirect to the console login
 *   403  → authenticated but not entitled to THIS tool
 * Anything else nginx maps to a 500, so this route must never redirect and must
 * never throw. It returns no body on success and proxies nothing: its whole job
 * is to answer "may this session reach this tool".
 *
 * This is the one route excluded from `src/proxy.ts` (ADR-0080 P0 kept the
 * matcher at "everything except Auth.js and static assets"). It has to be: the
 * middleware answers an unauthenticated request with a 302 to /auth/login, and
 * nginx turns any non-2xx/401/403 auth sub-response into a 500 — so the gate
 * would fail closed on exactly the case it exists to handle. The session check
 * below is the same one the middleware would have applied.
 */

const STATUS = { allow: 204, unauthenticated: 401, forbidden: 403 } as const

export async function GET(req: NextRequest): Promise<NextResponse> {
  // The allow-list, the deny-list and the session check live in `@/lib/auth/toolGate`,
  // shared with the Envoy contract (`./[tool]/[[...uri]]/route.ts`) so the two edges
  // cannot drift apart while both serve traffic during ADR-0324.
  const decision = await decideToolAccess(req.nextUrl.searchParams.get("tool") ?? "")
  return new NextResponse(null, { status: STATUS[decision], headers: { "Cache-Control": "no-store" } })
}
