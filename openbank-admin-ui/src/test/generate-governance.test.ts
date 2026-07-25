// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { beforeAll, describe, expect, it } from 'vitest'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
// @ts-expect-error - plain .mjs build script, no type declarations by design
import { buildManifest } from '../../scripts/generate-governance.mjs'

// Regression guard for issue #2165 — the ADR-0071 governance gate had two defects,
// both only observable on the gate's FAILURE path, which is why both survived:
//
//  1. schemaName was unconditionally required, so a genuinely stateless module
//     (openbank-ap2-service, openbank-mcp-service) could never be clean. The fix is
//     an EXPLICIT `stateless: true` assertion — statelessness must be declared, never
//     inferred from an absent field, or a forgotten schemaName looks identical to a
//     service that legitimately owns no schema.
//  2. The CI reporter read governance.json's non-existent `.modules` field, so it
//     threw a TypeError on every failure and had never once printed a gap. The
//     manifest now carries a top-level `gaps` string array; the tests below lock in
//     its presence, its length, and that it NAMES each offending module.
//
// The generator is imported, never spawned: `buildManifest` is the pure part and the
// script's CLI half only runs under a `process.argv[1]` entrypoint guard. Spawning a
// node subprocess per case measurably slowed the shared vitest pool and tipped
// unrelated render-smoke tests over their 5 s timeout.

interface Manifest {
  totals: { modules: number; withGaps: number }
  gaps: string[]
  services: Array<{ serviceName: string; schemaName: string | null; stateless?: true }>
}

const STATEFUL = `dataDomain: core
primaryDatastore: PostgreSQL
schemaName: widgets_schema
dataLineageRole: both
dataClassification: confidential
retentionPolicy: 10 years
`

const STATELESS = `dataDomain: payments
primaryDatastore: none
stateless: true
dataLineageRole: consumer
dataClassification: confidential
retentionPolicy: not applicable
`

function buildRepo(modules: Record<string, string | null>): Manifest {
  const repo = mkdtempSync(path.join(tmpdir(), 'gov-gate-'))
  try {
    for (const [name, yaml] of Object.entries(modules)) {
      const dir = path.join(repo, name)
      mkdirSync(dir, { recursive: true })
      writeFileSync(path.join(dir, 'version.txt'), '1.0.0\n') // released-component marker
      if (yaml != null) writeFileSync(path.join(dir, 'governance.yaml'), yaml)
    }
    return buildManifest(repo) as Manifest
  } finally {
    rmSync(repo, { recursive: true, force: true })
  }
}

const gapFor = (m: Manifest, name: string) => m.gaps.filter(g => g.startsWith(`${name}:`))

describe('generate-governance.mjs — the gap matrix (issue #2165)', () => {
  let m: Manifest

  beforeAll(() => {
    m = buildRepo({
      'openbank-ok-stateful': STATEFUL,
      'openbank-ok-stateless': STATELESS,
      'openbank-no-schemaname': STATEFUL.replace(/^schemaName:.*\n/m, ''),
      'openbank-stateless-with-schema': STATELESS + 'schemaName: contradiction_schema\n',
      'openbank-stateless-false': STATEFUL + 'stateless: false\n',
      'openbank-no-yaml': null,
    })
  })

  it('leaves a compliant stateful module and a compliant stateless module ungapped', () => {
    expect(gapFor(m, 'openbank-ok-stateful')).toEqual([])
    expect(gapFor(m, 'openbank-ok-stateless')).toEqual([])
  })

  it('flags a module that omits schemaName WITHOUT asserting statelessness', () => {
    const [gap, ...rest] = gapFor(m, 'openbank-no-schemaname')
    expect(rest).toEqual([])
    expect(gap).toContain('missing schemaName')
    expect(gap).toContain('stateless: true') // the remedy is spelled out in the message
  })

  it('flags the contradiction of `stateless: true` alongside a schemaName', () => {
    const [gap, ...rest] = gapFor(m, 'openbank-stateless-with-schema')
    expect(rest).toEqual([])
    expect(gap).toContain('stateless')
    expect(gap).toContain('contradiction_schema')
  })

  it('rejects `stateless: false` — the flag is an assertion, not a tri-state', () => {
    expect(gapFor(m, 'openbank-stateless-false')).toEqual([
      "openbank-stateless-false: stateless must be 'true' or omitted, got 'false'",
    ])
  })

  it('still flags a module with no governance.yaml at all', () => {
    expect(gapFor(m, 'openbank-no-yaml')).toEqual(['openbank-no-yaml: missing governance.yaml'])
  })

  it('records statelessness in the manifest so consumers can tell null-schema from unknown', () => {
    const stateless = m.services.find(s => s.serviceName === 'ok-stateless')
    expect(stateless).toMatchObject({ stateless: true, schemaName: null })
    expect(m.services.find(s => s.serviceName === 'ok-stateful')).toMatchObject({
      schemaName: 'widgets_schema',
    })
    expect(m.services.find(s => s.serviceName === 'ok-stateful')?.stateless).toBeUndefined()
  })

  // ── defect 2: the list the CI gate reads to name the offenders ──────────────
  it('emits a top-level `gaps` ARRAY matching totals.withGaps and naming every module', () => {
    expect(Array.isArray(m.gaps)).toBe(true)
    expect(m.gaps).toHaveLength(m.totals.withGaps)
    for (const name of [
      'openbank-no-schemaname',
      'openbank-stateless-with-schema',
      'openbank-stateless-false',
      'openbank-no-yaml',
    ]) {
      expect(m.gaps.join('\n')).toContain(name)
    }
  })
})

describe('generate-governance.mjs — a clean fleet (issue #2165)', () => {
  it('emits `gaps: []` (present, not undefined) when nothing is wrong', () => {
    const clean = buildRepo({ 'openbank-ok-stateful': STATEFUL, 'openbank-ok-stateless': STATELESS })
    expect(clean.totals).toMatchObject({ modules: 2, withGaps: 0 })
    expect(clean.gaps).toEqual([])
  })
})
