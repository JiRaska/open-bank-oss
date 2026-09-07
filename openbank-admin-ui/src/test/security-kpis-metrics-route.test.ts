// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { promises as fs } from 'fs'
import os from 'os'
import path from 'path'

// /api/security/kpis/metrics — Prometheus text exposition over the same snapshot
// as /api/security/kpis. The invariants that must never blur:
//   * always HTTP 200, even with no snapshot (scrape target stays up);
//   * unavailable collectors are OMITTED, never emitted as 0 — a 0 would read as
//     "no overdue credentials" and disarm the alerts that consume these series;
//   * every emitted series carries the const team="security" label.

let tmpDir: string

beforeEach(async () => {
  vi.resetModules()
  tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'kpis-metrics-'))
})

afterEach(() => {
  delete process.env.OPENBANK_SECURITY_KPIS
})

async function loadGet() {
  const { GET } = await import('@/app/api/security/kpis/metrics/route')
  return GET
}

async function scrape(fileContents: string | null): Promise<{ status: number; body: string; contentType: string }> {
  const file = path.join(tmpDir, 'security-kpis.json')
  if (fileContents === null) {
    process.env.OPENBANK_SECURITY_KPIS = path.join(tmpDir, 'does-not-exist.json')
  } else {
    await fs.writeFile(file, fileContents)
    process.env.OPENBANK_SECURITY_KPIS = file
  }
  const res = await (await loadGet())()
  return { status: res.status, body: await res.text(), contentType: res.headers.get('content-type') ?? '' }
}

const FULL_SNAPSHOT = {
  generatedAt: '2026-09-07T10:52:32.817696+00:00',
  netpol: { available: true, covered: 60, total: 75, coveragePct: 80, gateGreen: true },
  freshness: { available: true, fleetScore: 100, scoredModules: 3, unknownModules: 24 },
  credentials: { available: true, totalSecrets: 168, staticSecrets: 161, withDeadline: 3, overdue: 0, undeclared: 158, overdueFound: false },
  fuzz: { available: true, inScope: 26, tested: 26, coveragePct: 100, totalExercised: 4, excludedCount: 1, run: 'https://example/run/1', runDate: '2026-09-07' },
  threatModels: { available: true, moneyPathTotal: 23, withModel: 23, missing: [], staleCount: 0, oldestDays: 28 },
  mttr: { available: false, reason: 'dependabot alerts API answered 403' },
}

describe('GET /api/security/kpis/metrics', () => {
  it('exposes every available KPI as a labelled gauge with HELP/TYPE', async () => {
    const { status, body, contentType } = await scrape(JSON.stringify(FULL_SNAPSHOT))
    expect(status).toBe(200)
    expect(contentType).toContain('text/plain')
    expect(contentType).toContain('version=0.0.4')

    for (const [metric, value] of [
      ['openbank_security_kpi_netpol_coverage_pct', 80],
      ['openbank_security_kpi_dependency_freshness_score', 100],
      ['openbank_security_kpi_credentials_overdue', 0],
      ['openbank_security_kpi_credentials_undeclared', 158],
      ['openbank_security_kpi_fuzz_coverage_pct', 100],
      ['openbank_security_kpi_fuzz_tested_operations', 26],
      ['openbank_security_kpi_fuzz_in_scope_operations', 26],
      ['openbank_security_kpi_threat_models_stale', 0],
    ] as const) {
      expect(body).toContain(`# TYPE ${metric} gauge`)
      expect(body).toContain(`${metric}{team="security"} ${value}`)
    }
    // generatedAt converts to whole seconds.
    const ts = Math.floor(Date.parse('2026-09-07T10:52:32.817696+00:00') / 1000)
    expect(body).toContain(`openbank_security_kpi_generated_timestamp_seconds{team="security"} ${ts}`)
  })

  it('omits the dependabot gauge while the MTTR collector is unavailable — never a 0', async () => {
    const { body } = await scrape(JSON.stringify(FULL_SNAPSHOT))
    expect(body).not.toContain('openbank_security_kpi_dependabot_oldest_open_days')
  })

  it('emits the dependabot gauge when MTTR data exists', async () => {
    const snapshot = {
      ...FULL_SNAPSHOT,
      mttr: { available: true, severityScope: 'critical+high', fixedCount: 4, medianFixDays: 3.5, openCount: 2, oldestOpenDays: 21 },
    }
    const { body } = await scrape(JSON.stringify(snapshot))
    expect(body).toContain('openbank_security_kpi_dependabot_oldest_open_days{team="security"} 21')
  })

  it('omits any section whose collector degraded, keeping the rest', async () => {
    const snapshot = {
      ...FULL_SNAPSHOT,
      credentials: { available: false, reason: 'collector output unparsable' },
      freshness: { available: false, reason: 'no scored modules (Maven Central outage?)' },
    }
    const { status, body } = await scrape(JSON.stringify(snapshot))
    expect(status).toBe(200)
    expect(body).not.toContain('openbank_security_kpi_credentials_overdue')
    expect(body).not.toContain('openbank_security_kpi_credentials_undeclared')
    expect(body).not.toContain('openbank_security_kpi_dependency_freshness_score')
    expect(body).toContain('openbank_security_kpi_netpol_coverage_pct')
    expect(body).toContain('openbank_security_kpi_generated_timestamp_seconds')
  })

  it('omits non-numeric field values instead of emitting NaN', async () => {
    const snapshot = {
      ...FULL_SNAPSHOT,
      netpol: { available: true, coveragePct: 'eighty' },
    }
    const { body } = await scrape(JSON.stringify(snapshot))
    expect(body).not.toContain('openbank_security_kpi_netpol_coverage_pct')
    expect(body).not.toContain('NaN')
  })

  it('absent snapshot file is still HTTP 200 with no series', async () => {
    const { status, body } = await scrape(null)
    expect(status).toBe(200)
    expect(body).not.toContain('openbank_security_kpi_')
  })

  it('corrupt snapshot file is still HTTP 200 with no series', async () => {
    const { status, body } = await scrape('{not json')
    expect(status).toBe(200)
    expect(body).not.toContain('openbank_security_kpi_')
  })

  it('omits the timestamp gauge when generatedAt is missing or unparseable', async () => {
    const { generatedAt: _omit, ...rest } = FULL_SNAPSHOT
    const { body } = await scrape(JSON.stringify(rest))
    expect(body).not.toContain('openbank_security_kpi_generated_timestamp_seconds')
    const { body: badTs } = await scrape(JSON.stringify({ ...FULL_SNAPSHOT, generatedAt: 'not-a-date' }))
    expect(badTs).not.toContain('openbank_security_kpi_generated_timestamp_seconds')
  })
})
