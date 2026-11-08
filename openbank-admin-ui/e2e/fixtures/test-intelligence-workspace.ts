// SPDX-License-Identifier: Apache-2.0
import type { TestIntelligenceReport, TestCaseHistory, ComponentTestPosture } from '@/lib/types/test-intelligence'

export const workspaceObservedAt = '2026-11-08T12:00:00.000Z'
export const workspaceCase: TestCaseHistory = {
  fingerprint: 'case-1', component: 'openbank-account-service', kind: 'unit', classname: 'DuplicateRequestTest', name: 'rejects duplicate requests',
  testDefinitionPath: 'src/test/kotlin/DuplicateRequestTest.kt', owner: '@openbank/test-fixture', state: 'stable', lastState: 'passed', observations: 2,
  failureRate: 0, averageDurationMs: 12, wastedDurationMs: 0, sameCommitTransitions: 0, lastObservedAt: workspaceObservedAt,
  lastRun: { id: '101', attempt: 1, commit: 'a'.repeat(40), branch: 'main', workflow: 'Service tests', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/101', observedAt: workspaceObservedAt },
}
export const workspaceComponent: ComponentTestPosture = {
  component: 'openbank-account-service', domain: 'core', released: true, moneyPath: true,
  evidence: [{ kind: 'unit', state: 'passed', observedAt: workspaceObservedAt, source: 'JUnit:fixture', environment: 'ci', run: { id: '202', attempt: 1, commit: 'b'.repeat(40), branch: 'main', workflow: 'Aggregate tests', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/202' } }],
  coverage: { state: 'passed', observedAt: workspaceObservedAt, source: 'fixture', lines: { covered: 80, missed: 20, percentage: 80 }, branches: { covered: 8, missed: 2, percentage: 80 } },
  testInfrastructure: { declared: [], observed: [] },
}
export function workspaceReport(overrides: Partial<TestIntelligenceReport> = {}): TestIntelligenceReport {
  return {
    schemaVersion: 1, collectedAt: workspaceObservedAt, components: [workspaceComponent], testCases: [workspaceCase],
    contracts: [], mutations: [], performance: [], performanceHistory: [], syntheticJourneys: [], clientExperiences: [], history: [], runHistory: [],
    requiredControls: [{ id: 'openbank-account-service:mutation', component: 'openbank-account-service', kind: 'mutation', state: 'not-run', observedAt: null, source: null, reason: 'Required mutation evidence has not been retained.' }],
    totals: { components: 1, componentsWithExecutionEvidence: 1, moneyPathComponents: 1, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 }, warnings: [], ...overrides,
  }
}
