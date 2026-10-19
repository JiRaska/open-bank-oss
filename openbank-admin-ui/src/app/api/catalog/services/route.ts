// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { resolveAdminUiContent } from '@/lib/evidence/contentSource'

export const dynamic = 'force-dynamic'

// Serves the code-derived service catalog (ADR-0029 D3). Prefer the validated,
// independently published reviewed-source snapshot; use catalog.json from the
// image only as an explicitly labeled stale fallback. This is a read-only
// consumer (rule #3), never recomputed at runtime. If neither source is present,
// return the honest empty envelope (available:false).

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
  const resolved = await resolveAdminUiContent(
    snapshot => snapshot.content.catalog as unknown as Catalog,
    async () => {
      try { return JSON.parse(await fs.readFile(catalogFile(), 'utf-8')) as Catalog } catch { return null }
    },
  )
  const catalog = resolved.value
  const available = Boolean(catalog && Array.isArray(catalog.services))
  return NextResponse.json(
    { ...(available ? catalog : UNAVAILABLE), available, contentSource: resolved.contentSource },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
