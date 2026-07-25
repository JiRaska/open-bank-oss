// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { beforeAll, describe, expect, it } from 'vitest'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
import { fileURLToPath } from 'url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
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

// ── governance.schema.json is ENFORCED, not advisory ────────────────────────────
//
// The schema was referenced only from a comment while this generator re-implemented a
// SUBSET of it by hand, so the two drifted: four services shipped a bare `lineage:` key
// (YAML parses it to null; the schema says `type: object`) and the gate stayed green for
// months because `decl.lineage ?? undefined` tolerated it. The generator now compiles the
// schema with ajv and reports every violation the hand-written rules cannot express.
//
// Each case below is a KNOWN-POSITIVE: a deliberate violation that the pre-ajv gate
// accepted. A green run means nothing unless these are red without the schema layer.
describe('generate-governance.mjs — schema violations the hand rules cannot see', () => {
  it('flags a bare `lineage:` key (null, not an object) — the live drift on 4 services', () => {
    const m = buildRepo({ 'openbank-bare-lineage': STATEFUL + 'lineage:\n' })
    const [gap, ...rest] = gapFor(m, 'openbank-bare-lineage')
    expect(rest).toEqual([])
    expect(gap).toContain('governance.schema.json')
    expect(gap).toContain('/lineage')
    expect(gap).toContain('must be object')
  })

  it('flags an unknown top-level key (additionalProperties: false)', () => {
    const m = buildRepo({ 'openbank-typo-key': STATEFUL + 'retentionPolicyy: 3 years\n' })
    const [gap, ...rest] = gapFor(m, 'openbank-typo-key')
    expect(rest).toEqual([])
    expect(gap).toContain('must NOT have additional properties')
  })

  it('flags a malformed lineage node (relationType missing / out of enum)', () => {
    const m = buildRepo({
      'openbank-bad-node': STATEFUL + 'lineage:\n  upstream:\n    - serviceName: ledger-service\n',
      'openbank-bad-relation':
        STATEFUL + 'lineage:\n  upstream:\n    - serviceName: ledger-service\n      relationType: carrier-pigeon\n',
    })
    expect(gapFor(m, 'openbank-bad-node')[0]).toContain("must have required property 'relationType'")
    expect(gapFor(m, 'openbank-bad-relation')[0]).toContain('must be equal to one of the allowed values')
  })

  it('flags a wrongly-typed optional field (evidenceExported must be boolean)', () => {
    // Deliberately NOT one of the REQUIRED keys: the hand-written rules only check those for
    // presence, never for type, so this is red only because the schema is compiled.
    const m = buildRepo({ 'openbank-bad-flag': STATEFUL + 'evidenceExported: sometimes\n' })
    const [gap, ...rest] = gapFor(m, 'openbank-bad-flag')
    expect(rest).toEqual([])
    expect(gap).toContain('/evidenceExported')
    expect(gap).toContain('must be boolean')
  })

  // Two validators, one defect: the friendly rule owns the message, ajv stays quiet.
  it('does not double-report a defect the friendly rules already name', () => {
    const m = buildRepo({ 'openbank-bad-enum': STATEFUL.replace('dataDomain: core', 'dataDomain: banking') })
    expect(gapFor(m, 'openbank-bad-enum')).toEqual([
      'openbank-bad-enum: dataDomain=\'banking\' not in [core, payments, compliance, identity, open-banking, platform]',
    ])
  })

  // The enums and the required list are READ from governance.schema.json rather than
  // retyped — this is the drift that produced the bug, so it is locked in directly.
  it('derives its constraints from governance.schema.json, not from a hand-kept copy', () => {
    const schema = JSON.parse(
      readFileSync(path.resolve(HERE, '../../../openbank-libs/governance/governance.schema.json'), 'utf-8'),
    )
    // A required key the schema demands must produce a gap when absent.
    for (const key of schema.required as string[]) {
      const m = buildRepo({ 'openbank-x': STATEFUL.replace(new RegExp(`^${key}:.*\n`, 'm'), '') })
      expect(gapFor(m, 'openbank-x').join('\n'), `missing ${key} must gap`).toContain(key)
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
