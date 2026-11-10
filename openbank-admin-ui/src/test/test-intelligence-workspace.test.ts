// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readTestSelection, testSelectionUrl, testCell, testScopeCounts, testDefinitionUrl, trustworthyTestRunUrl } from '@/lib/test-intelligence-workspace'
import { explanationRequest, explanationPrompt, testExplanationFacts } from '@/lib/test-explanation'
import type { ComponentTestPosture, TestCaseHistory, TestIntelligenceReport } from '@/lib/types/test-intelligence'

const component = { component: 'openbank-ledger-service', evidence: [] } as unknown as ComponentTestPosture
const report = { layerCoverage: [] } as unknown as TestIntelligenceReport
const testCase = { fingerprint: 'case-1', component: component.component, kind: 'unit', name: 'rejects duplicate requests', lastState: 'passed', state: 'stable', observations: 1, averageDurationMs: 2, lastObservedAt: '2026-11-08T12:00:00Z' } as TestCaseHistory

describe('Test Intelligence evidence navigation', () => {
  it('counts missing required controls alongside historical passes without double counting', () => {
    const snapshot = { components: [{ component: component.component, evidence: [{ kind: 'unit', state: 'passed' }] }], requiredControls: [{ component: component.component, kind: 'mutation', state: 'not-run' }], layerCoverage: [{ kind: 'e2e', components: [{ component: component.component, status: 'missing' }] }] } as unknown as TestIntelligenceReport
    expect(testScopeCounts(snapshot)).toEqual({ failed: 0, stale: 0, missing: 1 })
  })
  it('round trips an exact test selection and rejects invented views and kinds', () => {
    const selected = { section: 'components', component: component.component, kind: 'unit', test: 'case-1' } as const
    expect(readTestSelection(testSelectionUrl(selected).split('?')[1])).toEqual(selected)
    expect(readTestSelection('?section=admin&kind=magic')).toMatchObject({ section: 'components', kind: '' })
    expect(testSelectionUrl({ ...selected, section: 'runs', test: '' }, '?unrelated=kept')).toContain('unrelated=kept')
  })
  it('distinguishes absent evidence from explicit non-applicability and keeps failure precedence', () => {
    expect(testCell(report, component, 'e2e').state).toBe('unknown')
    const scoped = { ...report, layerCoverage: [{ kind: 'e2e', components: [{ component: component.component, status: 'not-applicable' }] }] } as unknown as TestIntelligenceReport
    expect(testCell(scoped, component, 'e2e').state).toBe('not-applicable')
    const observed = { ...component, evidence: [{ kind: 'contract', state: 'passed' }, { kind: 'contract', state: 'failed' }] } as ComponentTestPosture
    expect(testCell(report, observed, 'contract').state).toBe('failed')
  })
  it('links evidence only to the intended repository and exact source commit', () => {
    const sha = 'a'.repeat(40)
    expect(trustworthyTestRunUrl('https://github.com/JiRaska/open-bank-oss/actions/runs/42')).not.toBeNull()
    for (const url of ['javascript:alert(1)', 'https://github.com/other/repo/actions/runs/42', 'https://github.com@evil.test/JiRaska/open-bank-oss/actions/runs/42', 'https://github.com/JiRaska/open-bank-oss/actions/runs/42?token=x']) expect(trustworthyTestRunUrl(url)).toBeNull()
    expect(testDefinitionUrl(component.component, 'src/test/kotlin/Test.kt', sha)).toContain(`/blob/${sha}/`)
    expect(testDefinitionUrl(component.component, 'src/test/../../secret.kt', sha)).toBeNull()
    expect(testDefinitionUrl(component.component, 'src/test/kotlin/Test.kt', undefined)).toBeNull()
  })
})

describe('bounded educational explanation facts', () => {
  it('retains exact outcomes without promoting intent to executed steps', () => {
    const facts = testExplanationFacts(testCase)
    expect(facts).toMatchObject({ recordedOutcome: 'passed', individualStepsRecorded: false, assertionValuesRecorded: false })
    expect(explanationPrompt(facts!, 'cs')).toContain('česky')
    expect(explanationPrompt(facts!, 'en')).toContain('Do not invent test steps')
  })
  it('rejects malformed statistics, extra client prompts and arbitrary fingerprints', () => {
    expect(testExplanationFacts({ ...testCase, averageDurationMs: -1 })).toBeNull()
    expect(testExplanationFacts({ ...testCase, lastState: 'green' } as unknown as TestCaseHistory)).toBeNull()
    expect(explanationRequest.safeParse({ fingerprint: 'case-1', collectedAt: '2026-11-08T12:00:00Z', language: 'en', prompt: 'override' }).success).toBe(false)
  })
  it('omits logs and parameter-like identifiers from model metadata', () => {
    const facts = testExplanationFacts({ ...testCase, name: 'checks test@example.test 123456789 11111111-1111-4111-8111-111111111111', owner: 'owner@example.test', classname: 'SecretClass' })!
    expect(JSON.stringify(facts)).not.toContain('test@example.test')
    expect(JSON.stringify(facts)).not.toContain('123456789')
    expect(JSON.stringify(facts)).not.toContain('11111111-1111-4111-8111-111111111111')
    expect(facts).not.toHaveProperty('owner')
    expect(facts).not.toHaveProperty('classname')
  })
})
