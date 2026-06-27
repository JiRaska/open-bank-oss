// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'

const BASE = process.env.SANCTIONS_SERVICE_URL ?? 'http://openbank-sanctions-service:8123'

export const dynamic = 'force-dynamic'

export async function GET(_req: NextRequest) {
  try {
    const res = await fetch(`${BASE}/api/v1/sanctions`, {
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    })
    const data = await res.json().catch(() => ({ error: 'Invalid JSON' }))
    if (!res.ok) return NextResponse.json(data, { status: res.status })
    return NextResponse.json(data, { headers: { 'Cache-Control': 'no-store' } })
  } catch (error) {
    return NextResponse.json({ error: error instanceof Error ? error.message : 'Checks request failed' }, { status: 502 })
  }
}
