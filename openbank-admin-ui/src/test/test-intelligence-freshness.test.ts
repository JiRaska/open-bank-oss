// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enforceRuntimeFreshness } from '@/lib/test-intelligence-freshness'
import type { EvidenceState, RequiredTestControl, TestIntelligenceReport } from '@/lib/types/test-intelligence'

const NOW = '2026-09-02T12:00:00.000Z'
const OLD = '2026-08-01T12:00:00.000Z'
const FRESH = '2026-09-01T12:00:00.000Z'

function control(state: EvidenceState, observedAt: string): RequiredTestControl {
  return {
    id: `openbank-ledger-service:unit:${state}`,
    component: 'openbank-ledger-service',
    kind: 'unit',
    state,
    reason: 'Unit execution is required.',
    source: 'run:v1',
    observedAt,
  }
}

function report(
  requiredControls?: RequiredTestControl[],
  componentState: EvidenceState = 'passed',
  observedAt = FRESH,
): TestIntelligenceReport {
  return {
    schemaVersion: 1,
    collectedAt: observedAt,
    components: [{
      component: 'openbank-ledger-service',
      released: true,
      moneyPath: true,
      evidence: [{ kind: 'unit', state: componentState, observedAt, source: 'run:v1', environment: 'ci' }],
      coverage: {
        state: 'not-run',
        observedAt: null,
        source: null,
        lines: { covered: 0, missed: 0, percentage: null },
        branches: { covered: 0, missed: 0, percentage: null },
      },
      testInfrastructure: { declared: [], observed: [] },
    }],
    contracts: [],
    mutations: [],
    performance: [],
    performanceHistory: [],
    syntheticJourneys: [],
    clientExperiences: [],
    ...(requiredControls ? { requiredControls } : {}),
    history: [],
    runHistory: [],
    testCases: [],
    totals: {
      components: 1,
      componentsWithExecutionEvidence: 1,
      moneyPathComponents: 1,
      failingEvidence: 0,
      missingEvidence: 0,
      staleEvidence: 0,
      ...(requiredControls ? {
        requiredControls: requiredControls.length,
        requiredControlGaps: requiredControls.filter(item => item.state !== 'passed').length,
      } : {}),
    },
    warnings: [],
  }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
  process.env.OPENBANK_TEST_INTELLIGENCE_STALE_AFTER_DAYS = '14'
})

afterEach(() => {
  delete process.env.OPENBANK_TEST_INTELLIGENCE_STALE_AFTER_DAYS
  vi.useRealTimers()
})

describe('test-intelligence runtime required-control freshness', () => {
  it('ages old passing component and required-control evidence and recomputes the gap', () => {
    const freshened = enforceRuntimeFreshness(report([control('passed', OLD)], 'passed', OLD))

    expect(freshened.components[0].evidence[0].state).toBe('stale')
    expect(freshened.requiredControls?.[0].state).toBe('stale')
    expect(freshened.totals).toMatchObject({ requiredControls: 1, requiredControlGaps: 1 })
  })

  it('keeps a fresh required-control pass out of the gap total', () => {
    const freshened = enforceRuntimeFreshness(report([control('passed', FRESH)]))

    expect(freshened.requiredControls?.[0].state).toBe('passed')
    expect(freshened.totals).toMatchObject({ requiredControls: 1, requiredControlGaps: 0 })
  })

  it('never promotes failed or blocked required controls', () => {
    const freshened = enforceRuntimeFreshness(report([
      control('failed', OLD),
      control('blocked', FRESH),
    ]))

    expect(freshened.requiredControls?.map(item => item.state)).toEqual(['failed', 'blocked'])
    expect(freshened.totals).toMatchObject({ requiredControls: 2, requiredControlGaps: 2 })
  })

  it('preserves reports that predate required controls', () => {
    const freshened = enforceRuntimeFreshness(report())

    expect(freshened).not.toHaveProperty('requiredControls')
    expect(freshened.totals).not.toHaveProperty('requiredControls')
    expect(freshened.totals).not.toHaveProperty('requiredControlGaps')
  })
})
