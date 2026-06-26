// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

// Serves the code-derived service catalog (ADR-0029 D3). The build bakes
// catalog.json via scripts/generate-catalog.mjs (mirrors test-results.json /
// cost-report.json); this route hands it to the admin-ui catalog page as a
// static, point-in-time snapshot — replacing the hand-maintained SERVICES array.
// READ-ONLY consumer (rule #3): never recomputed at runtime. If the snapshot is
// absent the route 200s with an empty, honest envelope (available:false), so the
// page degrades calmly instead of leaking a raw error.

interface CatalogService {
  name: string
  short: string
  kind: string
  releaseVersion: string | null
  apiVersion: string | null
  apiTitle: string | null
  hasOpenapi: boolean
  moneyPath: boolean
  gaps: string[]
}

interface Catalog {
  schema: string
  source: string
  collectedAt: string | null
  totals: Record<string, number>
  services: CatalogService[]
  available?: boolean
}

function catalogFile(): string {
  return process.env.OPENBANK_CATALOG ?? path.resolve(process.cwd(), 'catalog.json')
}

const UNAVAILABLE: Catalog = {
  schema: 'openbank.catalog/v1',
  source: 'no snapshot bundled',
  collectedAt: null,
  totals: {},
  services: [],
  available: false,
}

export async function GET() {
  try {
    const raw = await fs.readFile(catalogFile(), 'utf-8')
    const parsed = JSON.parse(raw) as Catalog
    if (Array.isArray(parsed?.services)) {
      return NextResponse.json({ ...parsed, available: true }, { headers: { 'Cache-Control': 'no-store' } })
    }
    return NextResponse.json(UNAVAILABLE, { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json(UNAVAILABLE, { headers: { 'Cache-Control': 'no-store' } })
  }
}
