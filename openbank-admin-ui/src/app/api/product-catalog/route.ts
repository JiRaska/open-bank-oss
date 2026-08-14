// SPDX-License-Identifier: Apache-2.0
import { NextRequest } from 'next/server'
import { productCatalogUpstream } from '@/lib/productCatalog/upstream'

export async function GET(req: NextRequest) {
  const search = req.nextUrl.search
  return productCatalogUpstream(`/api/v1/products${search}`)
}

export async function POST(req: NextRequest) {
  const body = await req.text()
  return productCatalogUpstream('/api/v1/products', { method: 'POST', body })
}
