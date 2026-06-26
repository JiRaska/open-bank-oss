// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { allocate, type CostReportLike, type ServiceFootprint } from '@/lib/finops/allocation'

export const dynamic = 'force-dynamic'

// ── Cost allocation (showback) — ADR-0062.
// Joins the AWS Cost Explorer snapshot (served as-is by /api/finops/costs) with the build-time
// resource footprints (cost-footprints.json) and rolls spend up the business taxonomy:
// service -> data domain (cost-center) -> business flow. The admin-ui stays a READ-ONLY consumer
// (rule #3): both inputs are baked/derived, the allocation maths is the pure allocate() function.
// On any missing input we 200 with available:false so the page degrades calmly — never an error.

// Cost report: same live > baked precedence as the costs route, so the allocation tracks the
// at-most-~1-day-fresh ConfigMap snapshot rather than a stale baked-in number.
function liveCostFile(): string | null {
  return process.env.OPENBANK_COST_REPORT_LIVE ?? null
}
function bakedCostFile(): string {
  return process.env.OPENBANK_COST_REPORT ?? path.resolve(process.cwd(), 'cost-report.json')
}
function footprintsFile(): string {
  return process.env.OPENBANK_COST_FOOTPRINTS ?? path.resolve(process.cwd(), 'cost-footprints.json')
}

async function readJson(file: string | null): Promise<unknown | null> {
  if (!file) return null
  try {
    return JSON.parse(await fs.readFile(file, 'utf-8'))
  } catch {
    return null
  }
}

// Normalise a cost snapshot from either producer (build collector emits numbers + total; the
// CronJob emits string amounts and omits total) into the shape allocate() expects.
function asCostReport(parsed: unknown): CostReportLike | null {
  if (!parsed || typeof parsed !== 'object') return null
  const p = parsed as Record<string, unknown>
  if (!Array.isArray(p.services)) return null
  const services = (p.services as { name?: unknown; amount?: unknown }[])
    .map(s => ({ name: String(s?.name ?? 'Unknown'), amount: Math.round(Number(s?.amount) * 100) / 100 }))
    .filter(s => Number.isFinite(s.amount) && s.amount > 0)
  const total = Math.round(services.reduce((sum, s) => sum + s.amount, 0) * 100) / 100
  return {
    available: services.length > 0 && p.available !== false,
    currency: typeof p.currency === 'string' ? p.currency : 'USD',
    periodStart: typeof p.periodStart === 'string' ? p.periodStart : '',
    periodEnd: typeof p.periodEnd === 'string' ? p.periodEnd : '',
    total,
    services,
    collectedAt: typeof p.collectedAt === 'string' ? p.collectedAt : null,
  }
}

function asFootprints(parsed: unknown): ServiceFootprint[] {
  if (!parsed || typeof parsed !== 'object') return []
  const p = parsed as Record<string, unknown>
  if (!Array.isArray(p.footprints)) return []
  return (p.footprints as { service?: unknown; cpuMillis?: unknown; memMiB?: unknown }[])
    .map(f => ({ service: String(f?.service ?? ''), cpuMillis: Number(f?.cpuMillis) || 0, memMiB: Number(f?.memMiB) || 0 }))
    .filter(f => f.service)
}

export async function GET() {
  const liveReport = asCostReport(await readJson(liveCostFile()))
  const report = liveReport?.available
    ? liveReport
    : asCostReport(await readJson(bakedCostFile()))
  const footprints = asFootprints(await readJson(footprintsFile()))

  const result = allocate(report ?? { available: false, currency: 'USD', total: 0, services: [] }, footprints)
  return NextResponse.json(result, { headers: { 'Cache-Control': 'no-store' } })
}
