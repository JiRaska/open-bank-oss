// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { resolveAdminUiContent } from '@/lib/evidence/contentSource'

export const dynamic = 'force-dynamic'

// Serves the code-derived governance manifest (ADR-0071), preferring the
// validated independent reviewed-source snapshot and explicitly labeling the
// image-baked governance.json fallback as stale. Runtime drift (live-DB Flyway
// current/drift) remains separate and unknown in /api/services/governance.
// READ-ONLY consumer: never recomputed at runtime.

interface GovernanceService {
  serviceName: string
  dataDomain: string | null
  primaryDatastore: string | null
  databaseName: string | null
  databaseNameEvidence?: 'derived' | 'declared-only' | null
  dataLineageRole: string | null
  dataClassification: string
  retentionPolicy: string
  evidenceExported?: boolean
  flywayDeclaredVersion: string | null
  lineage?: unknown
  databaseLineage?: unknown
}

interface GovernanceManifest {
  schema: string
  source: string
  collectedAt: string | null
  totals: Record<string, number>
  services: GovernanceService[]
  available?: boolean
}

function governanceFile(): string {
  return process.env.OPENBANK_GOVERNANCE ?? path.resolve(process.cwd(), 'governance.json')
}

const UNAVAILABLE: GovernanceManifest = {
  schema: 'openbank.governance/v1',
  source: 'no snapshot bundled',
  collectedAt: null,
  totals: {},
  services: [],
  available: false,
}

export async function GET() {
  const resolved = await resolveAdminUiContent(
    snapshot => snapshot.content.governance as unknown as GovernanceManifest,
    async () => {
      try { return JSON.parse(await fs.readFile(governanceFile(), 'utf-8')) as GovernanceManifest } catch { return null }
    },
  )
  const manifest = resolved.value
  const available = Boolean(manifest && Array.isArray(manifest.services))
  return NextResponse.json(
    { ...(available ? manifest : UNAVAILABLE), available, contentSource: resolved.contentSource },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
