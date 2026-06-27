// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'

const BASE = process.env.SANCTIONS_SERVICE_URL ?? 'http://openbank-sanctions-service:8123'

export const dynamic = 'force-dynamic'

export async function POST(_req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  try {
    // id here is the listType (e.g. OFAC_SDN)
    const { id } = await params
    const res = await fetch(`${BASE}/api/v1/sanctions/lists/${id}/refresh`, {
      method: 'POST',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(15_000),
    })
    const data = await res.json().catch(() => ({}))
    return NextResponse.json(data, { status: res.status })
  } catch (error) {
    return NextResponse.json({ error: error instanceof Error ? error.message : 'Refresh failed' }, { status: 502 })
  }
}
