// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'

export const dynamic = 'force-dynamic'

// ── FinOps tier classifier (ADR-0057, measured side) — rules.yaml: finops_tiers.classifier.
// Serves the daily markdown report written by the finops-tier-classifier CronJob
// (openbank-infra/scripts/finops-tier-classifier.py --report) into the
// admin-ui-tier-classifier ConfigMap (mounted at OPENBANK_TIER_CLASSIFIER): per-service
// declared-vs-recommended tier with drift flags, ready to render in a <pre>.
// House style (mirrors /api/finops/allocation): always 200, available:false before the
// first CronJob run — the admin-ui stays a READ-ONLY consumer (rule #3).

export async function GET() {
  const file = process.env.OPENBANK_TIER_CLASSIFIER
  let report: string | null = null
  if (file) {
    try {
      report = await fs.readFile(file, 'utf-8')
    } catch {
      report = null
    }
  }
  if (!report) {
    return NextResponse.json({
      available: false,
      reason: 'tier-classifier report not produced yet (the daily finops-tier-classifier CronJob has not landed)',
    })
  }
  const firstLine = report.split('\n', 1)[0] ?? ''
  return NextResponse.json({
    available: true,
    finding: firstLine.replace('FINOPS_TIER_CLASSIFIER_FINDING=', ''),
    report,
  })
}
