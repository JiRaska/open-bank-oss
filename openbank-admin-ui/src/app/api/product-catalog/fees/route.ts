// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
import { NextRequest, NextResponse } from 'next/server'

// Fees are served by the product catalog (system of record for pricing). The
// admin UI fetches them here instead of hardcoding a price list in the web tier.
const BASE = process.env.PRODUCT_CATALOG_URL ?? 'http://openbank-product-catalog:8104'

export const dynamic = 'force-dynamic'

export async function GET(req: NextRequest) {
  const search = req.nextUrl.search
  try {
    const res = await fetch(`${BASE}/api/v1/fees${search}`, {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    })
    const data = await res.json().catch(() => ({ error: 'Invalid JSON' }))
    return NextResponse.json(data, { status: res.status, headers: { 'Cache-Control': 'no-store' } })
  } catch (err: unknown) {
    const detail = err instanceof Error ? err.message : String(err)
    return NextResponse.json({ error: 'upstream_unreachable', detail }, { status: 502 })
  }
}
