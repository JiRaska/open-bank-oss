// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-side helper for the closings BFF (/api/closings/**, ADR-0069 D3).
//
// The browser must never reach statement-service directly (ADR-0056 / ADR-0080 P1):
// every call is proxied here, session-gated, with the operator's Keycloak bearer
// attached server-side. statement-service's CloseRunResource accepts the operator
// roles (ROLE_VIEWER/OPERATOR/ADMIN/AUDITOR for reads, ROLE_OPERATOR/ADMIN for the
// manual trigger), so the relayed operator token is the right credential — no
// service-account secret ever reaches the client.
//
// Error contract mirrors the generic /api/svc proxy so the client can reuse
// classifyBffFailure(): 401 {error:"unauthorized"} without a session,
// 502 {error:"upstream_unreachable"} when the pod doesn't answer. Upstream
// non-2xx bodies are replaced by a generic {error:"upstream_error"} — details
// stay in server logs, never in the browser.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission, Permission } from '@/lib/auth/roles'

const BASE = process.env.STATEMENT_SERVICE_URL ?? 'http://statement-service.statements.svc:8136'

/**
 * Session-gate the call, then forward `method <BASE><path>` with the operator's
 * bearer. 2xx responses (including the 204 "cadence never ran") pass through
 * verbatim; failures degrade to the stable BFF error contract above.
 *
 * `requiredPermission` enforces the admin-ui permission server-side (defense in
 * depth — upstream @RolesAllowed still applies): without it any signed-in role
 * could reach mutating endpoints and the UI gate would be cosmetic.
 */
export async function forwardToStatementService(
  path: string,
  method: 'GET' | 'POST' = 'GET',
  requiredPermission?: Permission,
): Promise<NextResponse> {
  const session = await auth()
  const accessToken = session?.user?.accessToken
  if (!accessToken) {
    return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  }
  if (requiredPermission && !hasPermission(session?.user?.roles ?? [], requiredPermission)) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }

  try {
    const res = await fetch(`${BASE}${path}`, {
      method,
      headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
      cache: 'no-store',
      // A manual catch-up pass closes every overdue pocket synchronously — give
      // the trigger more headroom than a plain read.
      signal: AbortSignal.timeout(method === 'POST' ? 30_000 : 8_000),
    })

    if (res.status === 204) {
      return new NextResponse(null, { status: 204, headers: { 'Cache-Control': 'no-store' } })
    }
    if (!res.ok) {
      // Generic to the client; the real body stays server-side (ADR-0080 P1).
      const detail = await res.text().catch(() => '')
      console.error(`closings BFF: upstream ${method} ${path} -> ${res.status} ${detail.slice(0, 500)}`)
      return NextResponse.json({ error: 'upstream_error' }, { status: res.status })
    }
    return new NextResponse(await res.arrayBuffer(), {
      status: res.status,
      headers: {
        'Content-Type': res.headers.get('Content-Type') ?? 'application/json',
        'Cache-Control': 'no-store',
      },
    })
  } catch (err) {
    console.error(`closings BFF: upstream ${method} ${path} unreachable:`, err)
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
