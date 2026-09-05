// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

// ── Security KPIs: READ-ONLY serving of the CI-generated snapshot ────────────
//
// Serves openbank-admin-ui/security-kpis.json — the weekly snapshot produced by
// .github/workflows/security-kpis.yml from the SAME scripts the gates run
// (netpol coverage, dependency freshness, credential inventory; ADR-0279
// #15/#17/#18). The admin-ui image bakes the file at build time
// (COPY openbank-admin-ui/ ./), exactly the security-report.json pattern
// documented in /api/security/route.ts: this route never computes, never
// triggers a scan, never talks to a cluster.
//
// Contract (ADR-0056 graceful degradation): ALWAYS HTTP 200 with a typed envelope
//   { available: true,  kpis }                    — have a snapshot
//   { available: false, reason: 'not_deployed' }  — no snapshot baked (older image)
//   { available: false, reason: 'error' }         — snapshot unreadable/corrupt
// Per-signal availability is carried INSIDE kpis (each collector can independently
// degrade), so the console renders three honest cards instead of one blanket error.

export const dynamic = 'force-dynamic'
export const revalidate = 0

type KpisEnvelope =
  | { available: true; kpis: unknown }
  | { available: false; reason: 'not_deployed' | 'error'; detail?: string }

function snapshotFile(): string {
  return process.env.OPENBANK_SECURITY_KPIS
    ?? path.resolve(process.cwd(), 'security-kpis.json')
}

export async function GET(): Promise<NextResponse> {
  let raw: string
  try {
    raw = await fs.readFile(snapshotFile(), 'utf-8')
  } catch {
    const payload: KpisEnvelope = { available: false, reason: 'not_deployed' }
    return NextResponse.json(payload, { status: 200 })
  }
  try {
    const kpis = JSON.parse(raw)
    const payload: KpisEnvelope = { available: true, kpis }
    return NextResponse.json(payload, { status: 200 })
  } catch {
    const payload: KpisEnvelope = { available: false, reason: 'error', detail: 'Invalid JSON in security-kpis.json' }
    return NextResponse.json(payload, { status: 200 })
  }
}
