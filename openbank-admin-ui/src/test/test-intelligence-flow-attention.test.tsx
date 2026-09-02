// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  TestIntelligenceFlow, testIntelligenceCollectionNeedsAttention,
  testIntelligenceCollectionUnavailable,
} from '@/components/testing/TestIntelligenceFlow'
import type { ComponentTestPosture, TestIntelligenceReport } from '@/lib/types/test-intelligence'

vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))

const healthyComponent: ComponentTestPosture = {
  component: 'openbank-example-service',
  released: true,
  moneyPath: false,
  evidence: [{
    kind: 'unit', state: 'passed', observedAt: '2026-09-01T00:00:00.000Z',
    source: 'JUnit:example', environment: 'ci',
  }],
  coverage: {
    state: 'passed', observedAt: '2026-09-01T00:00:00.000Z', source: 'kover.xml',
    lines: { covered: 1, missed: 0, percentage: 100 },
    branches: { covered: 1, missed: 0, percentage: 100 },
  },
  testInfrastructure: { declared: [], observed: [] },
}

function reportFixture({
  componentCount = 1,
  warnings = [],
  totals = {},
  syntheticJourneys = [],
}: {
  componentCount?: number
  warnings?: string[]
  totals?: Partial<TestIntelligenceReport['totals']>
  syntheticJourneys?: TestIntelligenceReport['syntheticJourneys']
} = {}): TestIntelligenceReport {
  return {
    schemaVersion: 1,
    collectedAt: '2026-09-01T00:00:00.000Z',
    components: componentCount === 0 ? [] : [healthyComponent],
    contracts: [], mutations: [], performance: [], performanceHistory: [],
    syntheticJourneys, clientExperiences: [], history: [], runHistory: [], testCases: [],
    totals: {
      components: componentCount, componentsWithExecutionEvidence: componentCount,
      moneyPathComponents: 0, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0,
      ...totals,
    },
    warnings,
  }
}

describe('Test Intelligence flow attention', () => {
  it('classifies zero inventory as unavailable without relying on a warning', () => {
    const report = reportFixture({ componentCount: 0 })

    expect(testIntelligenceCollectionUnavailable(report)).toBe(true)
    expect(testIntelligenceCollectionNeedsAttention(report)).toBe(true)
  })

  it('classifies a collector warning as attention without relying on zero inventory', () => {
    const report = reportFixture({ warnings: ['Tempo projection unavailable'] })

    expect(testIntelligenceCollectionUnavailable(report)).toBe(false)
    expect(testIntelligenceCollectionNeedsAttention(report)).toBe(true)
  })

  it('leaves a populated warning-free collection clear', () => {
    const report = reportFixture()

    expect(testIntelligenceCollectionUnavailable(report)).toBe(false)
    expect(testIntelligenceCollectionNeedsAttention(report)).toBe(false)
  })

  it('never paints the real unavailable-report shape healthy', () => {
    const report = reportFixture({
      componentCount: 0,
      warnings: ['test-intelligence.json is not bundled'],
    })

    render(<TestIntelligenceFlow report={report} />)

    const health = screen.getByText('NEEDS ATTENTION').closest('.ti-health')
    expect(health).toHaveTextContent(/NEEDS ATTENTION\s*1\s*signal to inspect/)
    expect(screen.queryByText('EVIDENCE HEALTHY')).not.toBeInTheDocument()
  })

  it('never paints unresolved evidence healthy', () => {
    const report = reportFixture({
      totals: { unknownEvidence: 1, unresolvedEvidence: 1 },
    })

    render(<TestIntelligenceFlow report={report} />)

    const health = screen.getByText('NEEDS ATTENTION').closest('.ti-health')
    expect(health).toHaveTextContent(/NEEDS ATTENTION\s*1\s*signal to inspect/)
  })

  it('does not hide a declared but unexecuted synthetic journey behind green component totals', () => {
    const report = reportFixture({
      syntheticJourneys: [{
        id: 'admin-operator-access', title: 'Admin access', status: 'planned', state: 'blocked',
      }] as TestIntelligenceReport['syntheticJourneys'],
    })

    render(<TestIntelligenceFlow report={report} />)

    const health = screen.getByText('NEEDS ATTENTION').closest('.ti-health')
    expect(health).toHaveTextContent(/NEEDS ATTENTION\s*1\s*signal to inspect/)
  })
})
