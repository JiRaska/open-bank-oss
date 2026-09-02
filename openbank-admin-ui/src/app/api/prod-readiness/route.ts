// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

// ── Source of truth: CI-produced readiness scorecard, baked into the image ───
//
// Production-readiness scores are a DERIVED CI artifact, exactly like the test
// /coverage summary (see api/test-results). The deploy build runs
// `openbank-infra/scripts/prod-readiness-collector.py --all --json` and bundles
// the resulting `prod-readiness.json` into the admin-ui image. The admin-ui is a
// READ-ONLY consumer — it never scores anything itself (rule #7: derived data is
// never hand-edited). The route always 200s with a typed body so the page
// degrades to a calm empty state, never a raw error.

interface ReadinessService {
  service: string
  money_path: boolean
  scores: Record<string, number>
  evidence: Record<string, string>
  gate: 'GO' | 'NO-GO' | 'NOT-DEPLOYED'
}

interface ReadinessReport {
  generated_for: string
  dimensions: { code: string; name: string }[]
  services: ReadinessService[]
}

function readinessFile(): string {
  return process.env.OPENBANK_READINESS
    ?? path.resolve(process.cwd(), 'prod-readiness.json')
}

export async function GET() {
  try {
    const raw = await fs.readFile(readinessFile(), 'utf-8')
    const parsed = JSON.parse(raw) as ReadinessReport
    if (Array.isArray(parsed.services) && Array.isArray(parsed.dimensions)) {
      return NextResponse.json(parsed)
    }
  } catch {
    // fall through to empty state
  }
  return NextResponse.json({ generated_for: '', dimensions: [], services: [] } as ReadinessReport)
}
