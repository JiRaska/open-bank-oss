// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BFF passthrough to Grafana Tempo's query API (ADR-0056 — the browser never
// reaches a cluster service directly). Mirrors the Prometheus proxy. Powers the
// Trace Explorer (/observability/traces): Tempo search (`/api/search`) and
// single-trace fetch (`/api/traces/{id}`, OTLP JSON). Read-only.
//
// Tempo serves its query API on port 3200 (tempo.observability.svc). In the
// docker-compose dev stack it is reachable as http://tempo:3200. Unreachable →
// a typed 502 the page degrades on (never a raw error leaked to the operator).

import { NextRequest, NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

function tempoBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://tempo:3200'
  return process.env.TEMPO_URL ?? 'http://localhost:3200'
}

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ path: string[] }> },
) {
  const { path: pathSegments } = await params
  const path = pathSegments.join('/')
  const search = req.nextUrl.search
  const target = `${tempoBase()}/${path}${search}`

  try {
    const upstream = await fetch(target, {
      signal: AbortSignal.timeout(8000),
      headers: { Accept: 'application/json' },
    })
    const body = await upstream.text()
    return new NextResponse(body, {
      status: upstream.status,
      headers: {
        'Content-Type': upstream.headers.get('Content-Type') ?? 'application/json',
        'Cache-Control': 'no-store',
      },
    })
  } catch {
    return NextResponse.json({ status: 'error', error: 'tempo_unreachable' }, { status: 502 })
  }
}
