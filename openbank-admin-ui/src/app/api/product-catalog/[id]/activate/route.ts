// SPDX-License-Identifier: MPL-2.0
import { NextRequest, NextResponse } from 'next/server'

const BASE = process.env.PRODUCT_CATALOG_URL ?? 'http://openbank-product-catalog:8104'

export async function POST(_req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const res = await fetch(`${BASE}/api/v1/products/${id}/activate`, {
    method: 'POST', headers: { Accept: 'application/json' },
    cache: 'no-store', signal: AbortSignal.timeout(10_000),
  })
  const data = await res.json().catch(() => ({ error: 'Invalid JSON' }))
  return NextResponse.json(data, { status: res.status })
}
