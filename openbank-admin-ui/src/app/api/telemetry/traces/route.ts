// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'

/**
 * Same-origin OTLP/HTTP relay for authenticated Admin UI browser RUM.
 * The browser never sees an in-cluster address; existing proxy authentication protects this route.
 */
export const runtime = 'nodejs'

const MAX_BODY_BYTES = 512 * 1024
const EXPORT_TIMEOUT_MS = 5_000

export async function POST(request: Request): Promise<NextResponse> {
  const endpoint = process.env.OTEL_EXPORTER_OTLP_ENDPOINT
  if (!endpoint) {
    return new NextResponse(null, { status: 204, headers: { 'x-rum-relay': 'disabled' } })
  }

  // Measure the encoded request body, not JavaScript characters: multi-byte Unicode must not
  // bypass the ingress budget intended to bound relay memory and collector load.
  const body = await request.arrayBuffer()
  if (body.byteLength > MAX_BODY_BYTES) {
    return NextResponse.json({ error: 'payload too large' }, { status: 413 })
  }

  try {
    const upstream = await fetch(`${endpoint.replace(/\/+$/, '')}/v1/traces`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body,
      signal: AbortSignal.timeout(EXPORT_TIMEOUT_MS),
    })
    if (!upstream.ok) {
      return new NextResponse(null, { status: 502, headers: { 'x-rum-relay': `upstream-${upstream.status}` } })
    }
    return new NextResponse(null, { status: 204, headers: { 'x-rum-relay': 'forwarded' } })
  } catch {
    return new NextResponse(null, { status: 502, headers: { 'x-rum-relay': 'unreachable' } })
  }
}
