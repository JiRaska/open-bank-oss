// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

// Serves the code-derived governance manifest (ADR-0071). The build bakes
// governance.json via scripts/generate-governance.mjs (joins per-service
// governance.yaml with derived Flyway versions); this route hands it to the
// admin-ui as a static, point-in-time snapshot — replacing the hand-maintained
// src/lib/governance/manifest.ts data (rule #7: derived, never hand-edited).
// READ-ONLY consumer: never recomputed at runtime. Runtime drift (live-DB Flyway
// current/drift) is merged in by the page from /api/services/governance.
// If the snapshot is absent the route 200s with an empty, honest envelope.

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
  try {
    const raw = await fs.readFile(governanceFile(), 'utf-8')
    const parsed = JSON.parse(raw) as GovernanceManifest
    if (Array.isArray(parsed?.services)) {
      return NextResponse.json({ ...parsed, available: true }, { headers: { 'Cache-Control': 'no-store' } })
    }
    return NextResponse.json(UNAVAILABLE, { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json(UNAVAILABLE, { headers: { 'Cache-Control': 'no-store' } })
  }
}
