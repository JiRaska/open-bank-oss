// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import {
  buildTestOverview, formatRelativeAge, lastRunFor, stateLabel,
} from '@/lib/test-intelligence-last-run'
import type { ComponentTestPosture, TestIntelligenceReport } from '@/lib/types/test-intelligence'

const NOW = Date.parse('2026-10-30T12:00:00Z')

function component(name: string, overrides: Partial<ComponentTestPosture> = {}): ComponentTestPosture {
  return {
    component: name, released: true, moneyPath: false, evidence: [],
    coverage: { state: 'not-run', observedAt: null, lines: { covered: 0, missed: 0, percentage: null }, branches: { covered: 0, missed: 0, percentage: null }, source: null },
    testInfrastructure: { declared: [], observed: [] },
    ...overrides,
  }
}

function report(overrides: Partial<TestIntelligenceReport> = {}): TestIntelligenceReport {
  return {
    schemaVersion: 1, collectedAt: '2026-10-30T11:00:00Z', components: [], contracts: [], mutations: [],
    performance: [], performanceHistory: [], syntheticJourneys: [], clientExperiences: [], history: [],
    runHistory: [], testCases: [], warnings: [],
    totals: { components: 0, componentsWithExecutionEvidence: 0, moneyPathComponents: 0, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 },
    ...overrides,
  }
}

const run = (observedAt: string) => ({ id: '1', attempt: 1, url: 'https://example.invalid/run', workflow: 'ci', branch: 'main', commit: 'abc1234', observedAt })

describe('lastRunFor', () => {
  it('prefers a real current observation', () => {
    const r = report({ components: [component('ledger', { evidence: [{ kind: 'e2e', state: 'passed', observedAt: '2026-10-30T10:00:00Z', source: 'ci', environment: 'ci' }] })] })
    expect(lastRunFor(r, 'ledger', 'e2e')).toEqual({ state: 'passed', observedAt: '2026-10-30T10:00:00Z', source: 'current' })
  })

  it('falls back to the newest retained run instead of reporting not-run', () => {
    const r = report({
      components: [component('ledger', { evidence: [{ kind: 'e2e', state: 'not-run', observedAt: null, source: 'ci', environment: 'ci' }] })],
      runHistory: [
        { component: 'ledger', run: run('2026-10-20T00:00:00Z'), states: { e2e: 'failed' }, infrastructureStarted: 0, infrastructureStopped: 0 },
        { component: 'ledger', run: run('2026-10-28T00:00:00Z'), states: { e2e: 'passed' }, infrastructureStarted: 0, infrastructureStopped: 0 },
        { component: 'ledger', run: run('2026-10-29T00:00:00Z'), states: { e2e: 'not-run' }, infrastructureStarted: 0, infrastructureStopped: 0 },
      ],
    })
    expect(lastRunFor(r, 'ledger', 'e2e')).toEqual({ state: 'passed', observedAt: '2026-10-28T00:00:00Z', source: 'history' })
  })

  it('returns null when the kind never ran', () => {
    expect(lastRunFor(report({ components: [component('ledger')] }), 'ledger', 'e2e')).toBeNull()
  })
})

describe('buildTestOverview', () => {
  it('never counts a component without a run as green', () => {
    const r = report({ components: [
      component('ledger', { moneyPath: true, evidence: [{ kind: 'unit', state: 'passed', observedAt: '2026-10-30T09:00:00Z', source: 'ci', environment: 'ci', counts: { discovered: 10, executed: 10, passed: 10, failed: 0, skipped: 0, errors: 0 } }] }),
      component('payments', { moneyPath: true }),
    ] })
    const overview = buildTestOverview(r, NOW)
    expect(overview.moneyPath).toEqual({ total: 2, allGreen: 1 })
    expect(overview.componentsRecentlyRun).toBe(1)
    expect(overview.verdict).toBe('attention')
    expect(overview.lastRunAt).toBe('2026-10-30T09:00:00Z')
    expect(overview.executed).toBe(10)
  })

  it('reports failing components first', () => {
    const r = report({ components: [component('ledger', { evidence: [{ kind: 'unit', state: 'failed', observedAt: '2026-10-30T09:00:00Z', source: 'ci', environment: 'ci' }] })] })
    expect(buildTestOverview(r, NOW)).toMatchObject({ verdict: 'failing', failingComponents: ['ledger'] })
  })

  it('says no-data rather than healthy when nothing was collected', () => {
    expect(buildTestOverview(report(), NOW).verdict).toBe('no-data')
  })
})

describe('labels', () => {
  it('formats ages and human state names', () => {
    expect(formatRelativeAge(null, 'cs')).toBe('nikdy')
    expect(formatRelativeAge(7200, 'en')).toBe('2 h')
    expect(formatRelativeAge(3 * 86400, 'cs')).toBe('3 d')
    expect(stateLabel('not-run', 'cs')).toBe('bez běhu')
  })
})
