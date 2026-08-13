// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

// Read-only consumer of a build-time snapshot (ADR-0255, mirrors ADR-0061's DORA route
// exactly). collect-gate-health.mjs runs inside admin-ui-deploy.yml's build job — which
// holds a `permissions: actions: read` GITHUB_TOKEN scoped to that one job and gone before
// this image ever runs — and writes gate-health.json. This pod never calls the GitHub API
// and never holds a token: `available: false` on a missing/stale snapshot, never a live
// fallback call from here.

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
  const snapshot = await readSnapshot()
  if (!snapshot || !snapshot.available) {
    return NextResponse.json({
      available: false,
      reason: snapshot?.reason ?? 'gate-health.json not found — collector has not run yet',
      collectedAt: snapshot?.collectedAt ?? null,
    })
  }
  return NextResponse.json(snapshot)
}
