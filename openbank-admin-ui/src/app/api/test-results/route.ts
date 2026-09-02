// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import type { Pool } from 'pg'
import type {
  ServiceTestResult,
  TestResultsResponse,
} from '@/lib/types/test-results'

export const dynamic = 'force-dynamic'

// ── Source of truth: CI-produced results, baked into the image ───────────────
//
// Test/coverage numbers are a CI artifact — `./gradlew koverXmlReport` /
// JUnit XML per service, summarised by the deploy build into a single
// `test-results.json` and bundled into the admin-ui image (mirrors the
// SBOM-bundle pattern in api/services/[name]/sbom). The admin-ui is a READ-ONLY
// consumer: it never runs tests itself. We prefer the bundled file; only if it
// is absent do we fall back to the live `openbank_ci` Postgres (legacy path,
// kept for the dev/compose setup). Either way the route always 200s with a
// typed body so the page degrades to a calm empty state, never a raw error.

function resultsFile(): string {
  return process.env.OPENBANK_TEST_RESULTS
    ?? path.resolve(process.cwd(), 'test-results.json')
}

/** Read the CI-bundled summary if present. Returns null when not bundled. */
async function fromBundle(): Promise<TestResultsResponse | null> {
  try {
    const raw = await fs.readFile(resultsFile(), 'utf-8')
    const parsed = JSON.parse(raw) as TestResultsResponse
    if (Array.isArray(parsed.services) && parsed.totals) return parsed
    return null
  } catch {
    return null
  }
}

function pgHost(): string {
  if (process.env.SERVICES_HOST === 'container') return 'openbank-postgres'
  return process.env.PGHOST ?? 'localhost'
}

let pool: Pool | null = null

// Lazily import `pg` so a module-load failure (e.g. the dependency not being
// traced into the standalone bundle) is caught and surfaced as a graceful
// "DB unavailable" 200 instead of crashing the route module — which Next would
// otherwise serve as a bare 404. This route must never hard-fail: the page that
// consumes it degrades to a calm empty state, never a raw HTTP status.
async function getPool(): Promise<Pool> {
  if (!pool) {
    const { Pool } = await import('pg')
    pool = new Pool({
      host:     pgHost(),
      port:     parseInt(process.env.PGPORT   ?? '5432'),
      user:     process.env.PGUSER            ?? 'openbank',
      password: process.env.PGPASSWORD        ?? 'openbank_secret',
      database: process.env.CI_PGDATABASE     ?? 'openbank_ci',
      max: 3,
      idleTimeoutMillis: 10000,
      connectionTimeoutMillis: 5000,
    })
  }
  return pool
}

const toInt = (v: unknown) => parseInt(v as string) || 0

export async function GET() {
  // Prefer the CI-bundled artifact; fall back to the live DB only if absent.
  const bundled = await fromBundle()
  if (bundled) {
    return NextResponse.json(bundled, { headers: { 'Cache-Control': 'no-store' } })
  }

  let summaryRows: Record<string, unknown>[] = []
  let typeRows:    Record<string, unknown>[] = []
  let dbError: string | null = null

  try {
    const client = await (await getPool()).connect()
    try {
      const [s, t] = await Promise.all([
        client.query('SELECT * FROM test_summary ORDER BY service'),
        client.query('SELECT * FROM test_summary_by_type ORDER BY service, test_type'),
      ])
      summaryRows = s.rows
      typeRows    = t.rows
    } finally {
      client.release()
    }
  } catch (e) {
    dbError = e instanceof Error ? e.message : String(e)
  }

  const byService = new Map(summaryRows.map(r => [r.service as string, r]))

  const byServiceType = new Map<string, { unit: Record<string, unknown> | null; integration: Record<string, unknown> | null }>()
  for (const r of typeRows) {
    const svc = r.service as string
    if (!byServiceType.has(svc)) byServiceType.set(svc, { unit: null, integration: null })
    const entry = byServiceType.get(svc)!
    if (r.test_type === 'unit')        entry.unit        = r
    if (r.test_type === 'integration') entry.integration = r
  }

  // The legacy DB has no fleet catalogue. Use exactly the services it has observed;
  // the normal bundled path is generated from release-please and is authoritative.
  const observedServices = [...new Set([...summaryRows, ...typeRows].map(r => String(r.service)))].sort()
  const services: ServiceTestResult[] = observedServices.map(name => {
    const r   = byService.get(name)
    const typ = byServiceType.get(name) ?? { unit: null, integration: null }
    const u   = typ.unit
    const i   = typ.integration

    if (!r) {
      return {
        service: name, tests: 0, passed: 0, failed: 0, skipped: 0, errors: 0,
        durationMs: 0, lastRunAt: null, testFiles: 0,
        unit:        { tests: 0, passed: 0, failed: 0 },
        integration: { tests: 0, passed: 0, failed: 0 },
      }
    }
    return {
      service:    name,
      tests:      toInt(r.tests),
      passed:     toInt(r.passed),
      failed:     toInt(r.failed),
      errors:     toInt(r.errors),
      skipped:    toInt(r.skipped),
      durationMs: toInt(r.duration_ms),
      lastRunAt:  r.last_run_at as string | null,
      testFiles:  toInt(r.test_files),
      unit:        { tests: toInt(u?.tests), passed: toInt(u?.passed), failed: toInt(u?.failed) + toInt(u?.errors) },
      integration: { tests: toInt(i?.tests), passed: toInt(i?.passed), failed: toInt(i?.failed) + toInt(i?.errors) },
    }
  })

  const totals: TestResultsResponse['totals'] = services.reduce(
    (acc, s) => ({
      tests:             acc.tests + s.tests,
      passed:            acc.passed + s.passed,
      failed:            acc.failed + s.failed,
      skipped:           acc.skipped + s.skipped,
      services:          acc.services + 1,
      servicesWithTests: acc.servicesWithTests + (s.tests > 0 ? 1 : 0),
      unit:        { tests: acc.unit.tests + s.unit.tests, passed: acc.unit.passed + s.unit.passed, failed: acc.unit.failed + s.unit.failed },
      integration: { tests: acc.integration.tests + s.integration.tests, passed: acc.integration.passed + s.integration.passed, failed: acc.integration.failed + s.integration.failed },
    }),
    { tests: 0, passed: 0, failed: 0, skipped: 0, services: 0, servicesWithTests: 0,
      unit: { tests: 0, passed: 0, failed: 0 }, integration: { tests: 0, passed: 0, failed: 0 } },
  )

  const body: TestResultsResponse = {
    services,
    totals,
    collectedAt: new Date().toISOString(),
    ...(dbError ? { error: dbError } : {}),
  }

  return NextResponse.json(body, { headers: { 'Cache-Control': 'no-store' } })
}
