// SPDX-License-Identifier: Apache-2.0

import { NextResponse } from 'next/server'
import { readFile } from 'fs/promises'
import path from 'path'
import { resolveAdminUiContent } from '@/lib/evidence/contentSource'

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
  const resolved = await resolveAdminUiContent(
    snapshot => snapshot.content.governance as { services?: GovernanceService[] },
    async () => {
      try { return JSON.parse(await readFile(governanceFile(), 'utf-8')) as { services?: GovernanceService[] } } catch { return null }
    },
  )
  const services = Array.isArray(resolved.value?.services) ? resolved.value.services : []
  const available = Array.isArray(resolved.value?.services)

  const items: ManifestItem[] = services.map(s => ({
    ...s,
    // runtime, no live-DB integration yet → honest unknowns, not fabricated values
    flywayCurrentVersion: null,
    flywayDrift: 'unknown',
  }))
  const byService: Record<string, ManifestItem> = {}
  for (const m of items) byService[m.serviceName] = m

  return NextResponse.json(
    { items, byService, available, timestamp: new Date().toISOString(), source: 'governance.json (ADR-0071)', contentSource: resolved.contentSource },
    { headers: { 'Cache-Control': 'no-store' } }
  )
}
