// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextRequest, NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

function prometheusBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return process.env.PROMETHEUS_URL ?? 'http://localhost:9090'
}

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ path: string[] }> },
) {
  const { path: pathSegments } = await params
  const path = pathSegments.join('/')
  const search = req.nextUrl.search
  const target = `${prometheusBase()}/${path}${search}`

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
    return NextResponse.json({ status: 'error', error: 'prometheus_unreachable' }, { status: 502 })
  }
}
