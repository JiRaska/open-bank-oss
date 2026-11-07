// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'node:fs'
import path from 'node:path'
import { createEvidenceSnapshotReader, type EvidenceProvenance } from '../../../lib/evidence/publishedSnapshot.ts'
import { isReadinessSnapshot, type ReadinessSnapshotReport } from '../../../lib/evidence/readinessSnapshot.ts'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type ReadinessResponse = {
  status: 'ready' | 'stale' | 'image-baked'
  source: 'runtime' | 'image-baked'
  report: ReadinessSnapshotReport
  provenance: EvidenceProvenance | null
  error?: string
}

type UnavailableResponse = { status: 'unavailable'; source: null; report: null; provenance: null }

const reader = createEvidenceSnapshotReader<ReadinessSnapshotReport>({
  baseUrl: () => {
    const override = process.env.OPENBANK_READINESS_EVIDENCE_URL
    if (override) return override
    const store = process.env.OPENBANK_EVIDENCE_STORE_URL
    return store ? `${store.replace(/\/+$/, '')}/admin-ui/readiness` : undefined
  },
  validateSnapshot: isReadinessSnapshot,
  collectedAt: report => report.collected_at,
})

function bakedFile(): string {
  return process.env.OPENBANK_READINESS ?? path.resolve(process.cwd(), 'prod-readiness.json')
}

async function readBakedSnapshot(): Promise<ReadinessSnapshotReport | null> {
  try {
    const parsed: unknown = JSON.parse(await fs.readFile(bakedFile(), 'utf-8'))
    return isReadinessSnapshot(parsed) ? parsed : null
  } catch {
    return null
  }
}

function json(body: ReadinessResponse | UnavailableResponse, status = 200) {
  return NextResponse.json(body, { status, headers: { 'Cache-Control': 'no-store' } })
}

export async function GET() {
  const live = await reader.read()
  if (live.snapshot && live.provenance) {
    return json({
      status: live.status,
      source: 'runtime',
      report: live.snapshot,
      provenance: live.provenance,
      ...(live.error ? { error: live.error } : {}),
    })
  }

  const baked = await readBakedSnapshot()
  if (baked) {
    // The image bundle is useful as a dated reference when the runtime publisher is disabled or
    // unavailable, but it is never described as current live evidence.
    return json({ status: 'image-baked', source: 'image-baked', report: baked, provenance: null })
  }

  return json({ status: 'unavailable', source: null, report: null, provenance: null }, 503)
}
