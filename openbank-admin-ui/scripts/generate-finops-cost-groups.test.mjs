// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { deriveCostGroups, deriveMoneyPathT0 } from './generate-finops-cost-groups.mjs'

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const source = readFileSync(path.join(repo, 'openbank-libs/governance/rules.yaml'), 'utf8')
const artifact = path.join(repo, 'openbank-admin-ui/finops-cost-groups.json')
const moneyPathArtifact = path.join(repo, 'openbank-admin-ui/finops-money-path.json')

describe('derived FinOps showback groups', () => {
  it('the baked artifact exactly matches the governed source', () => {
    assert.deepEqual(JSON.parse(readFileSync(artifact, 'utf8')), deriveCostGroups(source))
  })

  it('a source group change changes the derived artifact', () => {
    const changed = source.replace('  audit-trail:\n', '  audit-trail-mutated:\n')
    assert.notEqual(changed, source)
    assert.notDeepEqual(deriveCostGroups(changed), deriveCostGroups(source))
  })

  it('fails closed on duplicate keys and missing service lists', () => {
    assert.throws(() => deriveCostGroups('finops_cost_groups:\n  a: {label_en: A, label_cs: A, services: [a]}\n  a: {label_en: B, label_cs: B, services: [b]}\n'), /invalid rules.yaml/)
    assert.throws(() => deriveCostGroups('finops_cost_groups:\n  a: {label_en: A, label_cs: A, services: []}\n'), /nonempty service list/)
  })
})

describe('derived money-path T0 badges', () => {
  it('the baked service-card ids exactly match the governed source', () => {
    assert.deepEqual(JSON.parse(readFileSync(moneyPathArtifact, 'utf8')), deriveMoneyPathT0(source))
    assert(deriveMoneyPathT0(source).includes('pension'))
  })

  it('fails closed on malformed, duplicate, and colliding service ids', () => {
    assert.throws(() => deriveMoneyPathT0('money_path_services: []\n'), /nonempty unique/)
    assert.throws(() => deriveMoneyPathT0('money_path_services: [openbank-ledger-service, openbank-ledger-service]\n'), /nonempty unique/)
    assert.throws(() => deriveMoneyPathT0('money_path_services: [openbank-ledger, openbank-ledger-service]\n'), /collide/)
  })
})
