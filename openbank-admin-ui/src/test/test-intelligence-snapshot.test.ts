// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it } from 'vitest'
import {
  testIntelligenceCollectedAt,
  testIntelligenceSnapshotBaseUrl,
  validateTestIntelligenceSnapshot,
} from '@/lib/evidence/testIntelligenceSnapshot'

const report = (collectedAt = '2026-10-19T12:00:00.000Z') => ({
  schemaVersion: 1,
  collectedAt,
  components: [{
    component: 'openbank-new-service', released: true, moneyPath: false, evidence: [],
    coverage: {
      state: 'not-run', observedAt: null, source: null,
      lines: { covered: 0, missed: 0, percentage: null },
      branches: { covered: 0, missed: 0, percentage: null },
    },
    testInfrastructure: { declared: [], observed: [] },
  }],
  contracts: [], mutations: [], performance: [], performanceHistory: [], syntheticJourneys: [],
  clientExperiences: [], history: [], runHistory: [], testCases: [], warnings: [],
  totals: {
    components: 1, componentsWithExecutionEvidence: 0, moneyPathComponents: 0,
    failingEvidence: 0, missingEvidence: 1, staleEvidence: 0,
  },
})

describe('published Test Intelligence snapshot boundary', () => {
  afterEach(() => {
    delete process.env.OPENBANK_EVIDENCE_STORE_URL
    delete process.env.OPENBANK_TEST_INTELLIGENCE_SNAPSHOT_URL
  })

  it('accepts a valid roster-driven snapshot and exposes its collection timestamp', () => {
    const snapshot = report()
    expect(validateTestIntelligenceSnapshot(snapshot, Date.parse('2026-10-19T12:01:00Z'))).toBe(true)
    if (validateTestIntelligenceSnapshot(snapshot, Date.parse('2026-10-19T12:01:00Z'))) {
      expect(testIntelligenceCollectedAt(snapshot)).toBe(snapshot.collectedAt)
    }
  })

  it('rejects malformed, duplicate, and implausibly future-dated snapshots', () => {
    const duplicated = report()
    duplicated.components.push({ ...duplicated.components[0] })
    expect(validateTestIntelligenceSnapshot(duplicated, Date.parse('2026-10-19T12:01:00Z'))).toBe(false)

    const malformed = report()
    malformed.components[0].coverage.state = 'green'
    expect(validateTestIntelligenceSnapshot(malformed, Date.parse('2026-10-19T12:01:00Z'))).toBe(false)

    expect(validateTestIntelligenceSnapshot(report('2026-10-19T12:10:01Z'), Date.parse('2026-10-19T12:00:00Z'))).toBe(false)
  })

  it('rejects roster totals that could overstate the component or money-path population', () => {
    const wrongComponents = report()
    wrongComponents.totals.components = 2
    expect(validateTestIntelligenceSnapshot(wrongComponents, Date.parse('2026-10-19T12:01:00Z'))).toBe(false)

    const wrongMoneyPath = report()
    wrongMoneyPath.totals.moneyPathComponents = 1
    expect(validateTestIntelligenceSnapshot(wrongMoneyPath, Date.parse('2026-10-19T12:01:00Z'))).toBe(false)
  })

  it('derives the dataset endpoint from the shared evidence store and honors an explicit override', () => {
    process.env.OPENBANK_EVIDENCE_STORE_URL = 'https://evidence.example/store/'
    expect(testIntelligenceSnapshotBaseUrl()).toBe('https://evidence.example/store/admin-ui/test-intelligence')
    process.env.OPENBANK_TEST_INTELLIGENCE_SNAPSHOT_URL = 'https://override.example/ti'
    expect(testIntelligenceSnapshotBaseUrl()).toBe('https://override.example/ti')
  })
})
