// SPDX-License-Identifier: Apache-2.0

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

// Governance manifest for the fleet. Since ADR-0071 the source is the code-derived
// governance.json (baked by generate-governance.mjs from per-service governance.yaml
// + derived Flyway versions) — NOT the hand-edited manifest.ts. This route keeps its
// historical { items, byService, timestamp } contract so existing consumers
// (service-map, health via fetchAllGovernanceManifests) need no change.
//
// HONEST runtime fields: flywayCurrentVersion / flywayDrift require a live DB and
// there is no live-DB integration yet, so they are null / 'unknown' here rather than
// the fabricated static values the old manifest carried. When a live check lands it
// is merged in here. Declared posture (flywayDeclaredVersion, classification,
// lineage…) is real and derived.

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

// Shape consumers expect (the derived service + runtime fields).
interface ManifestItem extends GovernanceService {
  flywayCurrentVersion: string | null
  flywayDrift: boolean | 'unknown'
}

function governanceFile(): string {
  return process.env.OPENBANK_GOVERNANCE ?? path.resolve(process.cwd(), 'governance.json')
}

export async function GET() {
  let services: GovernanceService[] = []
  try {
    const raw = await fs.readFile(governanceFile(), 'utf-8')
    const parsed = JSON.parse(raw) as { services?: GovernanceService[] }
    if (Array.isArray(parsed?.services)) services = parsed.services
  } catch {
    services = [] // honest empty — pages degrade through the graceful-state rule
  }

  const items: ManifestItem[] = services.map(s => ({
    ...s,
    // runtime, no live-DB integration yet → honest unknowns, not fabricated values
    flywayCurrentVersion: null,
    flywayDrift: 'unknown',
  }))
  const byService: Record<string, ManifestItem> = {}
  for (const m of items) byService[m.serviceName] = m

  return NextResponse.json(
    { items, byService, timestamp: new Date().toISOString(), source: 'governance.json (ADR-0071)' },
    { headers: { 'Cache-Control': 'no-store' } }
  )
}
