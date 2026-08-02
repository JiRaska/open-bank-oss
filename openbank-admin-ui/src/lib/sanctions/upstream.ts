// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-side helper for the sanctions BFF (/api/sanctions/**, ADR-0056 / ADR-0080 P1).
//
// Why this file exists: the three sanctions routes each hand-rolled their own fetch and
// every one of them omitted the Authorization header. sanctions-service is OIDC-gated
// (@RolesAllowed on SanctionsResource), so every call answered 401, the page classified
// that as `unauthorized` and told a signed-in operator their session had expired — while
// the service was healthy and holding real OFAC/EU/UN list data. A signed-in operator saw
// "Vypršela relace" on a working screen, and the list tab rendered "Invalid JSON" because
// the 401 body is not the JSON the route expected.
//
// The bearer is the OPERATOR's Keycloak token relayed server-side (the same BFF token
// relay the generic /api/svc proxy documents) — never a service-account secret, and never
// anything the browser can see or set. Routing every sanctions call through one helper is
// the point: a per-route fetch is a per-route opportunity to forget the header again.
//
// Error contract mirrors the generic /api/svc proxy so the client can keep using
// classifyBffFailure(): 401 {error:"unauthorized"} without a session,
// 502 {error:"upstream_unreachable"} when the pod doesn't answer. Upstream non-2xx bodies
// are replaced by a generic {error:"upstream_error"} — details stay in the server log.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'

// Deployed, SANCTIONS_SERVICE_URL is the in-cluster ClusterIP DNS name. The fallback is the
// docker-compose service name, which is what local dev resolves.
const BASE = process.env.SANCTIONS_SERVICE_URL ?? 'http://openbank-sanctions-service:8123'

/**
 * Session-gate the call, then forward `method <BASE><path>` with the operator's bearer.
 *
 * `body` is sent as JSON when present. `timeoutMs` defaults to a read-shaped 10s; the
 * screening and refresh calls do real work upstream and pass a longer budget.
 *
 * `extraHeaders` exists for the four-eyes retry and is not optional decoration: the maker's
 * second call must carry `X-Approval-Id`, which `AuthorizeInterceptor.resolveApprovalIdHeader`
 * reads off the REQUEST. Dropping it does not error — the interceptor simply mints a fresh
 * PendingApproval and answers 202 again, so the operator loops forever with every individual
 * call looking healthy. Never let a caller reach the upstream except through this function;
 * a per-route fetch is a per-route opportunity to forget a header (that is how all three
 * original routes lost the bearer).
 */
export async function forwardToSanctionsService(
  path: string,
  method: 'GET' | 'PATCH' | 'POST' | 'PUT' = 'GET',
  body?: unknown,
  timeoutMs = 10_000,
  extraHeaders?: Record<string, string>,
): Promise<NextResponse> {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) {
    return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  }

  try {
    const res = await fetch(`${BASE}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        // Last, but Authorization is not overridable in practice: the only caller-supplied
        // header today is X-Approval-Id, and the review route allow-lists what it forwards
        // rather than passing the browser's headers through.
        ...(extraHeaders ?? {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      cache: 'no-store',
      signal: AbortSignal.timeout(timeoutMs),
    })

    if (!res.ok) {
      // Generic to the client; the real body stays server-side (ADR-0080 P1).
      const detail = await res.text().catch(() => '')
      console.error(`sanctions BFF: upstream ${method} ${path} -> ${res.status} ${detail.slice(0, 500)}`)
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
    console.error(`sanctions BFF: upstream ${method} ${path} unreachable:`, err)
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}
