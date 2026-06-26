// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextRequest, NextResponse } from 'next/server'

const BASE = process.env.SANCTIONS_SERVICE_URL ?? 'http://openbank-sanctions-service:8123'

export const dynamic = 'force-dynamic'

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const res = await fetch(`${BASE}/api/v1/sanctions/screen`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
      cache: 'no-store',
      signal: AbortSignal.timeout(15_000),
    })
    const data = await res.json().catch(() => ({ error: 'Invalid JSON' }))
    return NextResponse.json(data, { status: res.status, headers: { 'Cache-Control': 'no-store' } })
  } catch (error) {
    return NextResponse.json({ error: error instanceof Error ? error.message : 'Screening failed' }, { status: 502 })
  }
}
