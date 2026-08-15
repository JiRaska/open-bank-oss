// SPDX-License-Identifier: Apache-2.0
import { NextRequest } from 'next/server'
import { productCatalogUpstream } from '@/lib/productCatalog/upstream'

export async function GET(_req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  return productCatalogUpstream(`/api/v1/products/${encodeURIComponent(id)}`)
}

export async function PUT(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await req.text()
  return productCatalogUpstream(`/api/v1/products/${encodeURIComponent(id)}`, { method: 'PUT', body })
}
