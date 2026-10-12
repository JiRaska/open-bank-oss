// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Drift gate (ADR-0062 / ADR-0029 derive->enforce->show): the cost-group taxonomy mirror
// (costGroups.ts) must stay consistent with the governance manifest — every service named in a
// business flow must be a real, domain-classified service, or the roll-up would silently lose it.
// This fails the build the same way a Zod-validated manifest does.

import { describe, it, expect } from 'vitest'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import os from 'node:os'
import path from 'node:path'
import { parse as parseYaml } from 'yaml'
import { COST_GROUPS, costGroupsFromCatalog, domainForService, knownServiceIds, canonicalServiceId } from '@/lib/finops/costGroups'

describe('finops cost-group taxonomy', () => {
  it('the generated taxonomy matches every reviewed rules.yaml group and service', () => {
    const repo = path.resolve(__dirname, '../../..')
    const rules = parseYaml(readFileSync(path.join(repo, 'openbank-libs/governance/rules.yaml'), 'utf-8')) as {
      finops_cost_groups: Record<string, { label_en: string; label_cs: string; services: string[]; regulatory_ref?: string }>
    }
    const expected = Object.entries(rules.finops_cost_groups).map(([id, group]) => ({
      id, labelEn: group.label_en, labelCs: group.label_cs, services: group.services,
      ...(group.regulatory_ref ? { regulatoryRef: group.regulatory_ref } : {}),
    }))
    expect(COST_GROUPS).toEqual(expected)
    expect(COST_GROUPS.find(group => group.id === 'sepa-instant-flow')?.services).toContain('sepa-instant')
  })

  it('missing or malformed taxonomy cannot appear as an empty allocation', () => {
    expect(() => costGroupsFromCatalog({})).toThrow(/no FinOps/)
    expect(() => costGroupsFromCatalog({ finopsCostGroups: [{ id: 'x', labelEn: 'X', labelCs: 'X', services: [] }] }))
      .toThrow(/invalid/)
  })

  it('the generator refuses a flow that names a nonexistent module', () => {
    const repo = mkdtempSync(path.join(os.tmpdir(), 'finops-catalog-'))
    try {
      mkdirSync(path.join(repo, 'openbank-libs/governance'), { recursive: true })
      mkdirSync(path.join(repo, 'openbank-test-service'))
      const rulesFile = path.join(repo, 'openbank-libs/governance/rules.yaml')
      const script = path.resolve(__dirname, '../../scripts/generate-catalog.mjs')
      const out = path.join(repo, 'catalog.json')
      writeFileSync(rulesFile, 'money_path_services: [openbank-test-service]\nfinops_cost_groups:\n  example:\n    label_en: Example\n    label_cs: Příklad\n    services: [test-service]\n')
      expect(spawnSync(process.execPath, [script, '--repo', repo, '--out', out]).status).toBe(0)
      writeFileSync(rulesFile, 'money_path_services: [openbank-test-service]\nfinops_cost_groups:\n  example:\n    label_en: Example\n    label_cs: Příklad\n    services: [phantom-service]\n')
      const invalid = spawnSync(process.execPath, [script, '--repo', repo, '--out', out], { encoding: 'utf-8' })
      expect(invalid.status).not.toBe(0)
      expect(invalid.stderr).toContain('invalid finops_cost_groups entry: example')
    } finally {
      rmSync(repo, { recursive: true, force: true })
    }
  })

  it('every service in every flow exists in the governance manifest', () => {
    const known = knownServiceIds()
    for (const g of COST_GROUPS) {
      for (const svc of g.services) {
        expect(known.has(canonicalServiceId(svc)), `${g.id} -> ${svc}`).toBe(true)
      }
    }
  })

  it('every service in every flow resolves to a data domain', () => {
    for (const g of COST_GROUPS) {
      for (const svc of g.services) {
        expect(domainForService(svc), `${g.id} -> ${svc}`).toBeDefined()
      }
    }
  })

  it('has unique flow ids and non-empty bilingual labels', () => {
    const ids = COST_GROUPS.map(g => g.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const g of COST_GROUPS) {
      expect(g.labelEn.length, g.id).toBeGreaterThan(0)
      expect(g.labelCs.length, g.id).toBeGreaterThan(0)
      expect(g.services.length, g.id).toBeGreaterThan(0)
    }
  })

  it('canonicalServiceId strips a leading openbank- prefix', () => {
    expect(canonicalServiceId('openbank-ledger-service')).toBe('ledger-service')
    expect(canonicalServiceId('ledger-service')).toBe('ledger-service')
  })
})
