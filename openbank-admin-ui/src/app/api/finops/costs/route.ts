// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

// ── Source of truth: a CI-produced AWS Cost Explorer snapshot, baked into the
// image (ADR-0029 rule #3, mirrors test-results.json). The admin-ui is a
// READ-ONLY consumer — it never holds billing IAM at runtime. The deploy build
// runs scripts/collect-aws-costs.mjs with a `ce:GetCostAndUsage`-scoped role and
// bakes cost-report.json; this route serves it. If the snapshot is missing or
// reports available:false (CLI absent / access denied at collect time), we 200
// with available:false so the page degrades to a calm "cost data unavailable"
// state — never a fabricated number. Realises ADR-0054 phase 2 (periodic cost
// audit) without granting the pod live billing access.

// AWS service → business domain mapping (process view, ADR-0054).
const AWS_DOMAIN: Record<string, string> = {
  'EC2 - Other':                                     'Platform',
  'Amazon Elastic Compute Cloud - Compute':           'Platform',
  'Amazon Elastic Container Service for Kubernetes':  'Platform',
  'Amazon Virtual Private Cloud':                     'Platform',
  'Amazon Elastic Load Balancing':                    'Platform',
  'Amazon Route 53':                                  'Platform',
  'Amazon EC2 Container Registry (ECR)':              'Platform',
  'Amazon Simple Storage Service':                    'Platform',
  'AmazonCloudWatch':                                 'Observability',
  'AWS Key Management Service':                       'Security',
  'AWS Config':                                       'Governance',
  'AWS Cost Explorer':                                'FinOps',
  'Tax':                                              'Tax',
}
function domainFor(name: string): string { return AWS_DOMAIN[name] ?? 'Platform' }

export interface ServiceCost { name: string; amount: number; domain: string }

interface CostReport {
  available: boolean
  reason?: string
  currency: string
  periodStart: string
  periodEnd: string
  total: number
  services: ServiceCost[]
  collectedAt: string | null
  source: string
}

// Two sources, in precedence order:
//  1. LIVE — a ConfigMap the in-cluster cost-collector CronJob refreshes daily
//     (OPENBANK_COST_REPORT_LIVE, ADR-0054 phase 2). Preferred when present and
//     available, so the panel is at most ~1 day stale without an image rebuild.
//  2. BAKED — the build-time snapshot baked into the image (OPENBANK_COST_REPORT,
//     collect-aws-costs.mjs). Fallback so the panel still shows real numbers
//     before the first CronJob run (or if the live ConfigMap is unavailable).
function liveFile(): string | null {
  return process.env.OPENBANK_COST_REPORT_LIVE ?? null
}
function bakedFile(): string {
  return process.env.OPENBANK_COST_REPORT
    ?? path.resolve(process.cwd(), 'cost-report.json')
}

const UNAVAILABLE: CostReport = {
  available: false,
  reason: 'no snapshot available',
  currency: 'USD',
  periodStart: '',
  periodEnd: '',
  total: 0,
  services: [],
  collectedAt: null,
  source: 'aws-cost-explorer',
}

// Normalise a snapshot from either producer. The build-time collector emits
// clean numbers + total; the CronJob (aws-cli `--query`) emits amounts as
// strings and omits total. We coerce amounts to numbers, drop non-positive
// lines, sort desc, and always recompute total from the services so the two
// producers are indistinguishable downstream — never trusting a stale total.
function normalize(parsed: unknown): CostReport | null {
  if (!parsed || typeof parsed !== 'object') return null
  const p = parsed as Record<string, unknown>
  if (!Array.isArray(p.services)) return null
  const services = (p.services as { name?: unknown; amount?: unknown }[])
    .map(s => ({ name: String(s?.name ?? 'Unknown'), amount: Math.round(Number(s?.amount) * 100) / 100, domain: domainFor(String(s?.name ?? '')) }))
    .filter(s => Number.isFinite(s.amount) && s.amount > 0)
    .sort((a, b) => b.amount - a.amount)
  const total = Math.round(services.reduce((sum, s) => sum + s.amount, 0) * 100) / 100
  return {
    available: services.length > 0 && p.available !== false,
    reason: typeof p.reason === 'string' ? p.reason : undefined,
    currency: typeof p.currency === 'string' ? p.currency : 'USD',
    periodStart: typeof p.periodStart === 'string' ? p.periodStart : '',
    periodEnd: typeof p.periodEnd === 'string' ? p.periodEnd : '',
    total,
    services,
    collectedAt: typeof p.collectedAt === 'string' ? p.collectedAt : null,
    source: typeof p.source === 'string' ? p.source : 'aws-cost-explorer',
  }
}

async function readReport(file: string | null): Promise<CostReport | null> {
  if (!file) return null
  try {
    return normalize(JSON.parse(await fs.readFile(file, 'utf-8')))
  } catch {
    return null
  }
}

export async function GET() {
  const live = await readReport(liveFile())
  if (live?.available) {
    return NextResponse.json(live, { headers: { 'Cache-Control': 'no-store' } })
  }
  const baked = await readReport(bakedFile())
  if (baked?.available) {
    return NextResponse.json(baked, { headers: { 'Cache-Control': 'no-store' } })
  }
  // Neither source has usable data — surface the live reason if we have one.
  return NextResponse.json(live ?? baked ?? UNAVAILABLE, { headers: { 'Cache-Control': 'no-store' } })
}
