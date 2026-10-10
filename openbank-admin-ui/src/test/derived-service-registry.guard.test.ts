// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ── "No hand-kept fleet list" guard (enforced) ──────────────────────────────
//
// The console must show the CURRENT fleet, derived from the code-generated catalog
// (scripts/generate-catalog.mjs -> catalog.json), never from a list typed into a component. Eight
// such lists existed (api.ts, registry.ts, three API routes, docs/api, docs/service-map, the BFF
// proxy) and each covered a different 24-35 of 52+ service modules, so every new service was
// invisible to some screens and the screens disagreed with each other. Nothing failed: a short
// list reads as a complete one.
//
// Two checks:
//   1. the derived registry has exactly one entry per runnable service module on disk;
//   2. no source file under src/ (tests excluded) carries a hand-typed array of 5+ distinct
//      `openbank-<module>` names. The only sanctioned holder is registry.ts's SERVICE_OVERRIDES,
//      which is keyed by name for PRESENTATION metadata (label/group/naming exceptions) and cannot
//      add or remove a service - service-registry.guard.test.ts asserts every key is a real module.
//
// The scan is itself tested against a synthetic offender and against the pre-change sources of the
// eight files (see "scan detects ..." below) - a guard that has never seen the thing it forbids is
// decoration.

import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync, readdirSync, statSync } from 'fs'
import path from 'path'

import { buildRegistry, type CatalogFleetModule } from '@/lib/services/registry'

const ADMIN_UI = path.resolve(__dirname, '../..')
const REPO = path.resolve(ADMIN_UI, '..')
const SRC = path.join(ADMIN_UI, 'src')

const THRESHOLD = 5

/** The one file allowed to key presentation metadata by module name. */
const SANCTIONED = new Set(['src/lib/services/registry.ts'])

/**
 * Pre-existing hand-typed service lists that are NOT the fleet registry: each is a different
 * lens (BCP tiers, SLO targets, cost allocation groups, flag/doc ownership, probe targets, the
 * approvals and customer-graph fan-outs) with its own semantics, found when this guard was first
 * run. They are a ratchet, not an exemption: the set may only SHRINK (a baselined file that
 * becomes clean must be removed - the test below fails telling you to), and a NEW file with such a
 * list fails. Do not add to it; derive the set from the catalog instead.
 */
const BASELINE = new Set([
  'src/app/api/approvals/pending/route.ts',
  'src/app/api/bcp/health/route.ts',
  'src/app/api/customer-360/[partyId]/graph/route.ts',
  'src/app/api/pyrra/summary/route.ts',
  'src/app/docs/bcp/page.tsx',
  'src/app/temporal/page.tsx',
  'src/lib/context/customerGraph.ts',
  'src/lib/finops/costGroups.ts',
  'src/lib/governance/docs.ts',
  'src/lib/governance/flags.ts',
  'src/lib/infra/probes.ts',
])

/** Distinct quoted `openbank-*` module names in a source text. */
export function moduleNameLiterals(source: string): Set<string> {
  const out = new Set<string>()
  for (const m of source.matchAll(/(['"`])(openbank-[a-z0-9]+(?:-[a-z0-9]+)*)\1/g)) out.add(m[2])
  return out
}

/** Distinct quoted short names of the form `<x>-service`, the other spelling of the same list. */
export function shortServiceLiterals(source: string): Set<string> {
  const out = new Set<string>()
  for (const m of source.matchAll(/(['"`])([a-z0-9]+(?:-[a-z0-9]+)*-service)\1/g)) out.add(m[2])
  return out
}

export function looksLikeHandKeptFleet(source: string): boolean {
  return moduleNameLiterals(source).size >= THRESHOLD || shortServiceLiterals(source).size >= THRESHOLD
}

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap(entry => {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) return entry === 'test' || entry === 'generated' ? [] : walk(full)
    return /\.(ts|tsx)$/.test(entry) ? [full] : []
  })
}

function moduleDirs(): string[] {
  return readdirSync(REPO)
    .filter(e => e.startsWith('openbank-'))
    .filter(e => statSync(path.join(REPO, e)).isDirectory())
}

describe('no hand-kept service list (the fleet is derived from the catalog)', () => {
  it('the derived registry has one entry per runnable service module on disk', () => {
    const catalog = JSON.parse(readFileSync(path.join(ADMIN_UI, 'catalog.json'), 'utf-8')) as { services: CatalogFleetModule[] }
    const registry = buildRegistry(catalog.services)

    const runnableOnDisk = moduleDirs().filter(dir => {
      const gradle = path.join(REPO, dir, 'build.gradle.kts')
      if (!existsSync(gradle)) return false
      const src = readFileSync(gradle, 'utf-8')
      const viaConvention = /^\s*(?:plugins\s*\{\s*)?id\(["']openbank\.quarkus-service["']\)/m.test(src)
        && (src.includes('project(":openbank-libs-runtime")') || src.includes('project(":openbank-libs")'))
      return viaConvention || /^\s*alias\(libs\.plugins\.quarkus\)/m.test(src)
    })
    expect(runnableOnDisk.length).toBeGreaterThan(40)
    expect(registry.length).toBe(runnableOnDisk.length)

    // Every module that ships as a released component under the -service naming convention is in
    // the registry: the released-and-named-service axis is a subset of the runnable axis.
    const namedServices = moduleDirs().filter(d => d.endsWith('-service') && existsSync(path.join(REPO, d, 'version.txt')))
    const missing = namedServices.filter(d => !registry.some(s => s.container === d))
    expect(missing, `released *-service modules absent from the derived registry: ${missing.join(', ')}`).toEqual([])
  })

  it('no source file under src/ carries a hand-typed fleet list', () => {
    const offenders: string[] = []
    const flagged = new Set<string>()
    for (const file of walk(SRC)) {
      const rel = path.relative(ADMIN_UI, file).split(path.sep).join('/')
      if (SANCTIONED.has(rel)) continue
      const text = readFileSync(file, 'utf-8')
      if (!looksLikeHandKeptFleet(text)) continue
      flagged.add(rel)
      if (BASELINE.has(rel)) continue
      offenders.push(`${rel} (${moduleNameLiterals(text).size} openbank-* names, ${shortServiceLiterals(text).size} *-service names)`)
    }
    const stale = [...BASELINE].filter(f => !flagged.has(f))
    expect(stale, `BASELINE entries that no longer hold a hand-typed list - remove them (ratchet): ${stale.join(', ')}`).toEqual([])
    expect(
      offenders,
      'a hand-typed list of service names drifts from the fleet and from every other such list. '
      + 'Read the set from the catalog (getRegistry() on the server, buildRegistry() over '
      + '/api/catalog/services in the browser) and keep only presentation metadata in '
      + 'SERVICE_OVERRIDES: ' + offenders.join('; '),
    ).toEqual([])
  })

  it('scan detects a hand-typed fleet array (negative case: it must FAIL on one)', () => {
    const offender = [
      "const SERVICES = [",
      "  { name: 'a', container: 'openbank-account-service' },",
      "  { name: 'b', container: 'openbank-ledger-service' },",
      "  { name: 'c', container: 'openbank-balance-service' },",
      "  { name: 'd', container: 'openbank-party-service' },",
      "  { name: 'e', container: 'openbank-audit-service' },",
      ']',
    ].join('\n')
    expect(looksLikeHandKeptFleet(offender)).toBe(true)
    const shorts = ["'account-service'", "'ledger-service'", "'balance-service'", "'party-service'", "'audit-service'"].join(', ')
    expect(looksLikeHandKeptFleet(`const X = [${shorts}]`)).toBe(true)
    // Below the threshold and non-list usages stay quiet.
    expect(looksLikeHandKeptFleet("const a = 'openbank-account-service'; const b = 'openbank-ledger-service'")).toBe(false)
    // Duplicates of one name do not count as a list.
    expect(looksLikeHandKeptFleet("'openbank-x-service' 'openbank-x-service' 'openbank-x-service' 'openbank-x-service' 'openbank-x-service'")).toBe(false)
  })
})
