// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from "next/server"
import { decideToolAccess, toolCallbackPath } from "@/lib/auth/toolGate"

export const dynamic = "force-dynamic"

/**
 * ADR-0234 gate, Envoy Gateway contract (ADR-0324 Phase 2) — the `SecurityPolicy.extAuth`
 * target for the `/tools/<tool>` HTTPRoutes on the shared Gateway. Same decision as the nginx
 * contract in `../../route.ts` (`@/lib/auth/toolGate`); only the spelling of the answer
 * differs, because Envoy's HTTP ext_authz is not nginx's `auth_request`:
 *
 *   - ALLOW is 200, not 204. Envoy treats ONLY a 200 from an HTTP authorization service as
 *     OK; every other status — 204 included — is a denial whose status, headers and body are
 *     sent to the client. Measured against envoy v1.39.1 (EG v1.9.1's pinned proxy): the nginx
 *     route's 204 reached the browser as an empty 204 and the tool was never called.
 *   - UNAUTHENTICATED is a 302 to the console login, carrying the original URI as
 *     `callbackUrl`. Envoy Gateway has no `auth-signin` equivalent, but it forwards a
 *     denial's headers to the client verbatim, so the gate's own redirect IS the auth-signin
 *     redirect. The Location is relative, so it resolves against the host the browser asked —
 *     nothing here trusts a Host header.
 *   - FORBIDDEN stays 403.
 *
 * WHERE THE TOOL AND THE URI COME FROM. The SecurityPolicy sets `extAuth.http.path` to
 * `/api/gate/<tool>`, and Envoy APPENDS the original request path (with its query) to it:
 * `/tools/grafana/d/x?orgId=1` is checked as `/api/gate/grafana/tools/grafana/d/x?orgId=1`.
 * The first segment is therefore fixed by the policy, never by the caller — the same
 * property the nginx `?tool=` had — and the remainder is the browser's URI, used only as
 * the post-login destination and only when it stays under that tool's own prefix.
 *
 * Envoy issues the check with the ORIGINAL method (and no body), so every method answers.
 * Excluded from the middleware for the same reason as the nginx route (`src/proxy.ts`
 * matcher `api/gate` covers this subtree): the middleware's own redirect would not carry
 * the deep link, and the gate runs the same session check itself.
 */
async function gate(req: NextRequest, ctx: { params: Promise<{ tool: string; uri?: string[] }> }): Promise<NextResponse> {
  const { tool } = await ctx.params
  const decision = await decideToolAccess(tool)
  const headers = { "Cache-Control": "no-store" }

  if (decision === "allow") return new NextResponse(null, { status: 200, headers })
  if (decision === "forbidden") return new NextResponse(null, { status: 403, headers })

  // Strip `/api/gate/<tool>` to recover the URI the browser asked for.
  const prefix = `/api/gate/${encodeURIComponent(tool)}`
  const path = req.nextUrl.pathname.startsWith(prefix) ? req.nextUrl.pathname.slice(prefix.length) : ""
  const callback = toolCallbackPath(tool, `${path}${req.nextUrl.search}`)
  const location = callback
    ? `/auth/login?callbackUrl=${encodeURIComponent(callback)}`
    : "/auth/login"
  return new NextResponse(null, { status: 302, headers: { ...headers, Location: location } })
}

export const GET = gate
export const HEAD = gate
export const POST = gate
export const PUT = gate
export const PATCH = gate
export const DELETE = gate
export const OPTIONS = gate
