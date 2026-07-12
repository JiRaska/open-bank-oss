// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0030 D5 phase 1 (issue #861): reads the ConfigMap the sbom-drift-scanner
// CronJob writes daily — per money-path service, whether the running pod's image
// matches what GitOps (origin/main) currently declares. Absent before the first
// scan run (schema unknown, not an error) — the route returns 503 and the UI
// shows "not yet scanned", same convention as /api/infra/lifecycle.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'
export const revalidate = 0

interface DriftEntry {
  status: 'checked' | 'no-pod-found'
  runningImage?: string
  declaredImage?: string
  inSync?: boolean
}
interface DriftSnapshot {
  scannedAt?: string | null
  services?: Record<string, DriftEntry>
}

async function readSnapshot(): Promise<DriftSnapshot | null> {
  const file = process.env.OPENBANK_SBOM_DRIFT ?? path.resolve(process.cwd(), 'sbom-drift.json')
  try {
    return JSON.parse(await fs.readFile(file, 'utf-8')) as DriftSnapshot
  } catch {
    return null
  }
}

export async function GET() {
  const snap = await readSnapshot()
  if (!snap?.services) {
    return NextResponse.json({ error: 'not yet scanned' }, { status: 503 })
  }
  return NextResponse.json(
    { schema: 'openbank.sbom-drift/v1', scannedAt: snap.scannedAt ?? null, services: snap.services },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
