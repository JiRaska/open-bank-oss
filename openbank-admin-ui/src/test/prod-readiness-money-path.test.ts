// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { execFileSync } from 'child_process'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, rmSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
import { afterAll, describe, expect, it } from 'vitest'

// The readiness collector's money-path set decides which services face the STRICT gate
// (C1/C5/C7 >= 3) rather than the lenient one (all >= 2). It used to be a hand-copied literal
// "mirroring" rules.yaml, and it drifted: rules.yaml declared 23 money-path services, the
// literal listed 14. Nine were scored leniently — billing and sdd read GO in the shipped
// matrix — and three suffixless modules (sepa-payment, sepa-instant, domestic-payment) had no
// row at all. Both defects were fixed in the Python collector under #2364, but the deploy build
// runs the .mjs, so the shipped artifact kept the stale literal (#2365).
//
// These tests assert the property that makes a repeat impossible: the set is DERIVED, every
// declared money-path service gets a row and the strict gate, and an unreadable list fails loudly
// instead of relaxing the gate for everyone.

const REPO = path.resolve(__dirname, '..', '..', '..')
const SCRIPT = path.join(REPO, 'openbank-admin-ui', 'scripts', 'collect-prod-readiness.mjs')
const CATALOG_SCRIPT = path.join(REPO, 'openbank-admin-ui', 'scripts', 'generate-catalog.mjs')
const tmps: string[] = []

function declaredMoneyPath(): Set<string> {
  const text = readFileSync(path.join(REPO, 'openbank-libs', 'governance', 'rules.yaml'), 'utf-8')
  const out = new Set<string>()
  for (const line of text.split('money_path_services:')[1].split('\n')) {
    const m = line.match(/^\s+-\s+openbank-([a-z0-9-]+?)(?:-service)?\s*(?:#.*)?$/)
    if (m) { out.add(m[1]); continue }
    if (line.trim() && !line.trim().startsWith('#') && !line.startsWith('    ')) break
  }
  return out
}

function runCollector(repo = REPO): { report: any; catalog: any } {
  const dir = mkdtempSync(path.join(tmpdir(), 'readiness-'))
  tmps.push(dir)
  const out = path.join(dir, 'prod-readiness.json')
  const catalogOut = path.join(dir, 'catalog.json')
  execFileSync('node', [SCRIPT, '--repo', repo, '--out', out], { stdio: 'pipe' })
  execFileSync('node', [CATALOG_SCRIPT, '--repo', repo, '--out', catalogOut], { stdio: 'pipe' })
  return { report: JSON.parse(readFileSync(out, 'utf-8')), catalog: JSON.parse(readFileSync(catalogOut, 'utf-8')) }
}

describe('readiness collector money-path set', () => {
  afterAll(() => { for (const d of tmps) rmSync(d, { recursive: true, force: true }) })

  const { report, catalog } = runCollector()
  const services = report.services
  const byName = new Map(services.map(s => [s.service, s]))
  const declared = declaredMoneyPath()

  it('declares a non-trivial money-path set (guards the parser itself)', () => {
    // A known-positive: if this parse silently returned {} the assertions below would all
    // pass vacuously, which is the exact failure this test exists to prevent.
    expect(declared.size).toBeGreaterThan(15)
    expect(declared.has('ledger')).toBe(true)
  })

  it('publishes the verified canonical service-module and money-path scope with provenance', () => {
    const canonicalServices = catalog.services
      .filter((service: { runnable?: boolean }) => service.runnable === true)
      .map((service: { name: string }) => service.name.replace(/^openbank-/, '').replace(/-service$/, ''))
      .sort()
    expect(services.map((service: { service: string }) => service.service).sort()).toEqual(canonicalServices)
    expect(report.scope.kind).toBe('service-module-matrix-v1')
    expect(report.scope.money_path_count).toBe(declared.size)
    const canonicalRoster = report.services.map((service: { service: string; money_path: boolean }) => `${service.service}:${service.money_path}`).sort().join('\n')
    expect(report.scope.roster_sha256).toBe(createHash('sha256').update(canonicalRoster).digest('hex'))
    expect(report.schema_version).toBe(1)
    expect(report.collected_at).toMatch(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/)
    expect(report.source_sha).toMatch(/^[0-9a-f]{40}$/)
  })

  it('scores a row for every service rules.yaml declares money-path', () => {
    const missing = [...declared].filter(s => !byName.has(s))
    expect(missing).toEqual([])
  })

  it('keeps runnable analytics-sink visible with explicit release and governance evidence gaps', () => {
    const analytics = catalog.services.find((service: { name: string }) => service.name === 'openbank-analytics-sink')
    expect(analytics?.runnable).toBe(true)
    expect(analytics?.gaps).toContain('runnable backend component is not release-registered')
    expect(analytics?.gaps).toContain('runnable backend component has no governance.yaml')
    expect(byName.has('analytics-sink')).toBe(true)
  })

  it('marks every declared money-path service money_path, with no drift in either direction', () => {
    const collected = new Set(services.filter(s => s.money_path).map(s => s.service))
    expect([...collected].sort()).toEqual([...declared].sort())
  })

  it('applies the strict gate: no declared money-path service reads GO without bank-grade C1/C5/C7', () => {
    // billing and sdd read GO before the fix purely because they were absent from the literal.
    const scored = services.filter(s => s.money_path) as unknown as
      { service: string; gate: string; scores: Record<string, number> }[]
    for (const s of scored) {
      if (s.gate === 'GO') {
        for (const c of ['C1', 'C5', 'C7']) expect(s.scores[c]).toBeGreaterThanOrEqual(3)
      }
    }
  })

  it('refuses to score when the money-path list cannot be read, instead of relaxing the gate', () => {
    const fake = mkdtempSync(path.join(tmpdir(), 'readiness-norules-'))
    tmps.push(fake)
    mkdirSync(path.join(fake, 'openbank-libs', 'governance'), { recursive: true })
    writeFileSync(path.join(fake, 'openbank-libs', 'governance', 'rules.yaml'), 'other_key: []\n')
    mkdirSync(path.join(fake, 'openbank-ledger-service'), { recursive: true })
    expect(() => runCollector(fake)).toThrow()
  })
})
