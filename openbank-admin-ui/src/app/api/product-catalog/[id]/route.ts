// SPDX-License-Identifier: MPL-2.0
import { NextRequest, NextResponse } from 'next/server'

const BASE = process.env.PRODUCT_CATALOG_URL ?? 'http://openbank-product-catalog:8104'

async function proxy(path: string, method = 'GET', body?: string) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: method !== 'GET' && body ? body : undefined,
    cache: 'no-store',
    signal: AbortSignal.timeout(10_000),
  })
  const data = await res.json().catch(() => ({ error: 'Invalid JSON' }))
  return NextResponse.json(data, { status: res.status })
}

export async function GET(_req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  return proxy(`/api/v1/products/${id}`)
}

export async function PUT(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await req.text()
  return proxy(`/api/v1/products/${id}`, 'PUT', body)
}
