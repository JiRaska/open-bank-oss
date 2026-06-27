// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import type { QualityReport } from '@/lib/types/quality-report'

export const dynamic = 'force-dynamic'

// Quality report — contract verification results + pitest mutation scores (ADR-0063).
// Source: quality-report.json bundled into the admin-ui image by scripts/collect-quality-report.mjs
// during the build-push step. Falls back to an empty scaffold when the file is absent
// (dev / no-CI environments).

function reportFile(): string {
  return process.env.OPENBANK_QUALITY_REPORT
    ?? path.resolve(process.cwd(), 'quality-report.json')
}

async function fromBundle(): Promise<QualityReport | null> {
  try {
    const raw = await fs.readFile(reportFile(), 'utf-8')
    const parsed = JSON.parse(raw) as QualityReport
    if (Array.isArray(parsed.contracts) && Array.isArray(parsed.mutations)) return parsed
    return null
  } catch {
    return null
  }
}

const EMPTY: QualityReport = {
  contracts: [],
  mutations: [],
  serviceScores: [],
  collectedAt: new Date(0).toISOString(),
  error: 'quality-report.json not bundled — run a CI build to generate it',
}

export async function GET(): Promise<NextResponse> {
  const report = await fromBundle()
  return NextResponse.json(report ?? EMPTY, { status: 200 })
}
