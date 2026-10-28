// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { createGateHealthSnapshotReader, validateGateHealthSnapshotData } from '@/lib/evidence/gateHealthSnapshot'

export const dynamic = 'force-dynamic'
const runtimeSnapshot = createGateHealthSnapshotReader()

// Runtime evidence is published independently of the application image. The BFF
// verifies the selector and content digest; its last-good and bundled fallbacks
// carry explicit provenance. GitHub credentials remain in the producer job.

interface GateSummary {
  id: string
  group: string
  mode: string
  status: string
  lastRed: { runId: number; sha: string; createdAt: string } | null
  flaky: boolean
  selftestDeclared: boolean
  minSubjects: number | null
  budgetSeconds: number | null
  runsObserved: number
}

interface ShardRun {
  runId: number
  sha: string
  event: string
  createdAt: string
  shards: { name: string; conclusion: string | null; seconds: number | null }[]
}

interface Estate {
  total: number
  enforced: number
  advisory: number
  withSelftest: number
  withFloor: number
  withBudget: number
  flaky: number
}

interface GateHealthSnapshot {
  available: boolean
  reason?: string
  source: string
  collectedAt: string
  runsInspected: number
  gateDetailRunsInspected: number
  shardHistory: ShardRun[]
  gates: GateSummary[]
  estate: Estate | null
}

async function readSnapshot(): Promise<GateHealthSnapshot | null> {
  const file = process.env.OPENBANK_GATE_HEALTH_REPORT
    ?? path.resolve(process.cwd(), 'gate-health.json')
  try {
    const raw = await fs.readFile(file, 'utf8')
    return JSON.parse(raw) as GateHealthSnapshot
  } catch {
    return null
  }
}

export async function GET() {
  const runtime = await runtimeSnapshot.read()
  const headers = { 'Cache-Control': 'private, no-store' }
  if (runtime.snapshot) {
    return NextResponse.json({ ...runtime.snapshot, evidence: {
      state: runtime.status, reason: runtime.error, provenance: runtime.provenance,
    } }, { headers })
  }
  const snapshot = await readSnapshot()
  if (!validateGateHealthSnapshotData(snapshot)) {
    return NextResponse.json({
      available: false,
      reason: runtime.error ?? snapshot?.reason ?? 'No verified Gate Health snapshot is available',
      collectedAt: snapshot?.collectedAt ?? null,
      evidence: { state: 'unavailable' },
    }, { headers })
  }
  // A bundled compatibility snapshot is never represented as a fresh runtime publication.
  return NextResponse.json({ ...snapshot, evidence: {
    state: runtime.status === 'disabled' ? 'bundled' : 'stale',
    reason: runtime.error ?? 'Bundled compatibility snapshot; runtime publisher is not configured',
  } }, { headers })
}
