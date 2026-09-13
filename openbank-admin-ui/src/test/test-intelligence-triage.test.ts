// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import type { TestCaseHistory } from '@/lib/types/test-intelligence'
import { filterTestCases } from '@/lib/test-intelligence-triage'

const testCase = (overrides: Partial<TestCaseHistory>): TestCaseHistory => ({
  fingerprint: 'test-fingerprint', component: 'openbank-ledger-service', kind: 'integration',
  classname: 'LedgerFlowIT', name: 'posts a payment', owner: '@openbank/core', state: 'stable',
  lastState: 'passed', observations: 3, failureRate: 0, averageDurationMs: 20, wastedDurationMs: 0,
  sameCommitTransitions: 0, lastObservedAt: '2026-08-26T12:00:00.000Z', ...overrides,
})

describe('filterTestCases', () => {
  const cases = [
    testCase({ fingerprint: 'stable', name: 'reads balance' }),
    testCase({ fingerprint: 'flaky', state: 'flaky', sameCommitTransitions: 1, wastedDurationMs: 90 }),
    testCase({ fingerprint: 'failing', state: 'failing', lastState: 'failed', wastedDurationMs: 20 }),
    testCase({ fingerprint: 'skipped', state: 'skipped', lastState: 'skipped' }),
  ]

  it('prioritises actionable failures without changing their verdict', () => {
    expect(filterTestCases(cases, 'all', '').map(item => item.fingerprint)).toEqual(['failing', 'flaky', 'skipped', 'stable'])
    expect(filterTestCases(cases, 'failing', '')).toEqual([expect.objectContaining({ fingerprint: 'failing', state: 'failing' })])
  })

  it('finds only observed instability and supports provenance search', () => {
    expect(filterTestCases(cases, 'unstable', '').map(item => item.fingerprint)).toEqual(['flaky'])
    expect(filterTestCases(cases, 'all', 'ledgerflowit').map(item => item.fingerprint)).toEqual(['failing', 'flaky', 'skipped', 'stable'])
    expect(filterTestCases(cases, 'all', '@openbank/core').map(item => item.fingerprint)).toHaveLength(4)
    expect(filterTestCases(cases, 'all', 'does-not-exist')).toEqual([])
  })
})
