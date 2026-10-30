// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Drift guard for ADR-0227 D1/D3: src/lib/approvals/risk.ts carries a static copy of the
// action-class metadata in openbank-libs/governance/rules.yaml (four_eyes.verbs,
// four_eyes.actions, money_path_services, money_path_action_prefixes). A rules.yaml change
// without the matching copy update silently re-buckets the inbox risk filter — this test
// fails instead. Same contract as the repo's other copy-vs-source guards.

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { parse } from 'yaml'
import { describe, expect, it } from 'vitest'
import { classifyApprovalRisk, RISK_RULE_COPY } from '@/lib/approvals/risk'

const here = dirname(fileURLToPath(import.meta.url))
const rulesYaml = parse(readFileSync(resolve(here, '../../../openbank-libs/governance/rules.yaml'), 'utf8'))

const NORMALISE = (name: string) => name.replace(/^openbank-/, '').replace(/-service$/, '')

function rulesYamlSection<T>(path: string[]): T {
  let node: unknown = rulesYaml
  for (const key of path) {
    node = (node as Record<string, unknown>)[key]
  }
  return node as T
}

describe('approval risk map matches rules.yaml (ADR-0227 D1/D3 drift guard)', () => {
  it('fourEyesVerbs equals rules.yaml four_eyes.verbs', () => {
    const fromYaml = new Set(rulesYamlSection<string[]>(['four_eyes', 'verbs']))
    expect([...RISK_RULE_COPY.fourEyesVerbs].sort()).toEqual([...fromYaml].sort())
  })

  it('fourEyesActions equals rules.yaml four_eyes.actions', () => {
    const fromYaml = new Set(rulesYamlSection<string[]>(['four_eyes', 'actions']))
    expect([...RISK_RULE_COPY.fourEyesActions].sort()).toEqual([...fromYaml].sort())
  })

  it('moneyPathScopes equals rules.yaml money_path_services, normalised the way rest.rego derives scopes', () => {
    const fromYaml = new Set(rulesYamlSection<string[]>(['money_path_services']).map(NORMALISE))
    expect([...RISK_RULE_COPY.moneyPathScopes].sort()).toEqual([...fromYaml].sort())
  })

  it('actionPrefixOverrides equals rules.yaml money_path_action_prefixes', () => {
    const fromYaml = rulesYamlSection<Record<string, string[]>>(['money_path_action_prefixes'])
    // The BFF map flattens each scope's prefix list into per-prefix entries.
    const flattened: Record<string, string> = {}
    for (const [scope, prefixes] of Object.entries(fromYaml)) {
      for (const prefix of prefixes) flattened[prefix] = scope
    }
    expect(RISK_RULE_COPY.actionPrefixOverrides).toEqual(flattened)
  })

  it('classifies the fleet-known actions the way rest.rego four_eyes_required does', () => {
    // Money-path scope + gated verb.
    expect(classifyApprovalRisk('ledger', 'ledger.reverse')).toBe('MONEY_PATH')
    expect(classifyApprovalRisk('fx', 'fx.convert')).toBe('MONEY_PATH')
    // Action-prefix override: device/scaChallenge derive the sca scope.
    expect(classifyApprovalRisk('sca', 'device.enroll')).toBe('DESTRUCTIVE') // exact four_eyes.action wins
    expect(classifyApprovalRisk('sca', 'device.revoke')).toBe('MONEY_PATH') // sca scope + revoke verb
    // Exact actions, independent of money-path scope.
    expect(classifyApprovalRisk('notification', 'opsmessage.compose')).toBe('DESTRUCTIVE')
    expect(classifyApprovalRisk('party', 'party.merge')).toBe('DESTRUCTIVE')
    expect(classifyApprovalRisk('settlement', 'settlement.create')).toBe('DESTRUCTIVE')
    // Money-path domain but the four-eyes lives in the domain, not in the OPA metadata.
    expect(classifyApprovalRisk('treasury', 'treasury.MM_PLACEMENT')).toBe('OPERATIONAL')
    // Non-money-path, unlisted verb.
    expect(classifyApprovalRisk('identity-case', 'identity.case.secondApproval')).toBe('OPERATIONAL')
  })
})
