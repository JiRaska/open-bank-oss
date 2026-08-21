// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'

/**
 * Same-origin OTLP/HTTP relay for browser RUM spans (issue #5735).
 *
 * The browser POSTs OTLP/JSON here; this handler forwards it to the in-cluster collector.
 * See `src/lib/telemetry/rum.ts` for why the browser cannot POST to the public rum-gateway
 * directly (no CORS on its receiver, wrong OIDC realm, and this app's CSP allows
 * `connect-src 'self'` only).
 *
 * The route sits behind the existing next-auth middleware (`src/proxy.ts`), so an
 * unauthenticated caller never reaches it — that is the auth story, and it is why no token
 * has to be minted for the collector hop.
 *
 * OTEL_EXPORTER_OTLP_ENDPOINT is read at REQUEST time on the server, NOT a `NEXT_PUBLIC_*`
 * value: those are inlined into the bundle at build time and cannot vary per environment
 * without a rebuild. Unset ⇒ this route answers 204 and drops the batch, so RUM is off by
 * default rather than failing loudly in an environment with no collector.
 */
export const runtime = 'nodejs'

/** Cap the relayed body; the collector's own receiver caps at 512k. */
const MAX_BODY_BYTES = 512 * 1024

const EXPORT_TIMEOUT_MS = 5_000

export async function POST(request: Request): Promise<NextResponse> {
  const endpoint = process.env.OTEL_EXPORTER_OTLP_ENDPOINT
  if (!endpoint) {
    // Not configured for this environment — accept and drop. Distinguishable from a
    // successful relay by the header, so "RUM is off" is never mistaken for "RUM works".
    return new NextResponse(null, { status: 204, headers: { 'x-rum-relay': 'disabled' } })
  }

  const body = await request.text()
  if (body.length > MAX_BODY_BYTES) {
    return NextResponse.json({ error: 'payload too large' }, { status: 413 })
  }

  const url = `${endpoint.replace(/\/+$/, '')}/v1/traces`
  try {
    const upstream = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body,
      signal: AbortSignal.timeout(EXPORT_TIMEOUT_MS),
    })
    if (!upstream.ok) {
      return new NextResponse(null, {
        status: 502,
        headers: { 'x-rum-relay': `upstream-${upstream.status}` },
      })
    }
    return new NextResponse(null, { status: 204, headers: { 'x-rum-relay': 'forwarded' } })
  } catch {
    // Telemetry must never surface as an operator-visible failure; the browser exporter
    // drops the batch on a non-2xx and moves on.
    return new NextResponse(null, { status: 502, headers: { 'x-rum-relay': 'unreachable' } })
  }
}
