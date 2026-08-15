// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import { NextRequest } from 'next/server'
import { productCatalogUpstream } from '@/lib/productCatalog/upstream'

// Fees are served by the product catalog (system of record for pricing). The
// admin UI fetches them here instead of hardcoding a price list in the web tier.
export const dynamic = 'force-dynamic'

export async function GET(req: NextRequest) {
  const search = req.nextUrl.search
  return productCatalogUpstream(`/api/v1/fees${search}`)
}
