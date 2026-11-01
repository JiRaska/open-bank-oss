// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { parseSecurityKpis } from '@/lib/security/kpiContract'
import { classifySecurityKpiFreshness } from '@/lib/security/kpiFreshness'
import { securityKpiSnapshotReader } from '@/lib/security/kpiSnapshot'
import type { EvidenceProvenance } from '@/lib/evidence/publishedSnapshot'

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
export const runtime = 'nodejs'

type KpisEnvelope =
  | { available: true; kpis: unknown; status: 'ready' | 'stale'; source: 'runtime' | 'image-baked'; provenance: EvidenceProvenance | null; detail?: string }
  | { available: false; reason: 'not_deployed' | 'error'; detail?: string }

function snapshotFile(): string {
  return process.env.OPENBANK_SECURITY_KPIS
    ?? path.resolve(process.cwd(), 'security-kpis.json')
}

export async function GET(): Promise<NextResponse> {
  const live = await securityKpiSnapshotReader.read()
  if (live.snapshot && live.provenance) {
    const payload: KpisEnvelope = {
      available: true,
      kpis: live.snapshot,
      status: live.status,
      source: 'runtime',
      provenance: live.provenance,
      ...(live.error ? { detail: live.error } : {}),
    }
    return NextResponse.json(payload, { status: 200, headers: { 'Cache-Control': 'no-store' } })
  }
  // Keep the image-baked path for local development and older deployments only when
  // no independent evidence store is configured. A configured but stale/unavailable
  // publisher must never silently fall back to a possibly older image copy.
  if (live.status !== 'disabled') {
    const payload: KpisEnvelope = { available: false, reason: 'error', detail: live.error ?? 'Published security KPI snapshot is unavailable' }
    return NextResponse.json(payload, { status: 200, headers: { 'Cache-Control': 'no-store' } })
  }
  let raw: string
  try {
    raw = await fs.readFile(snapshotFile(), 'utf-8')
  } catch {
    const payload: KpisEnvelope = { available: false, reason: 'not_deployed' }
    return NextResponse.json(payload, { status: 200 })
  }
  try {
    const kpis = parseSecurityKpis(JSON.parse(raw))
    const payload: KpisEnvelope = {
      available: true,
      kpis,
      status: classifySecurityKpiFreshness(kpis.generatedAt) === 'fresh' ? 'ready' : 'stale',
      source: 'image-baked',
      provenance: null,
    }
    return NextResponse.json(payload, { status: 200 })
  } catch {
    const payload: KpisEnvelope = { available: false, reason: 'error', detail: 'Invalid or contradictory security-kpis.json evidence' }
    return NextResponse.json(payload, { status: 200 })
  }
}
