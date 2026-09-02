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
  components,
  warnings = [],
  totals = {},
  syntheticJourneys = [],
  clientExperiences = [],
}: {
  componentCount?: number
  components?: ComponentTestPosture[]
  warnings?: string[]
  totals?: Partial<TestIntelligenceReport['totals']>
  syntheticJourneys?: TestIntelligenceReport['syntheticJourneys']
  clientExperiences?: TestIntelligenceReport['clientExperiences']
} = {}): TestIntelligenceReport {
  const reportComponents = components ?? (componentCount === 0 ? [] : [healthyComponent])
  return {
    schemaVersion: 1,
    collectedAt: '2026-09-01T00:00:00.000Z',
    components: reportComponents,
    contracts: [], mutations: [], performance: [], performanceHistory: [],
    syntheticJourneys, clientExperiences, history: [], runHistory: [], testCases: [],
    totals: {
      components: reportComponents.length, componentsWithExecutionEvidence: reportComponents.length,
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

  it('does not hide failed client execution evidence behind green service-component totals', () => {
    const report = reportFixture({
      clientExperiences: [{
        id: 'openbank-app', title: 'OpenBank app', surface: 'mobile', platforms: ['android', 'ios'],
        evidence: [{
          kind: 'e2e', state: 'failed', observedAt: '2026-09-01T00:00:00.000Z',
          source: 'openbank-app CI', environment: 'ci',
        }, {
          kind: 'unit', state: 'stale', observedAt: '2026-08-01T00:00:00.000Z',
          source: 'openbank-app CI', environment: 'ci',
        }],
        rum: {
          state: 'passed', policy: 'consent-gated', detail: 'Runtime signal is healthy.',
          observedAt: '2026-09-01T00:00:00.000Z',
          platforms: [
            { platform: 'android', capability: 'passed', runtime: 'passed', detail: 'Observed.' },
            { platform: 'ios', capability: 'passed', runtime: 'passed', detail: 'Observed.' },
          ],
        },
        blocker: null,
      }],
    })

    render(<TestIntelligenceFlow report={report} />)

    const health = screen.getByText('NEEDS ATTENTION').closest('.ti-health')
    expect(health).toHaveTextContent(/NEEDS ATTENTION\s*2\s*signals to inspect/)
    expect(screen.queryByText('EVIDENCE HEALTHY')).not.toBeInTheDocument()
  })

  it('does not count Admin UI execution twice when the same run is also component evidence', () => {
    const failedEvidence = {
      kind: 'e2e' as const, state: 'failed' as const, observedAt: '2026-09-01T00:00:00.000Z',
      source: 'openbank-admin-ui CI', environment: 'ci',
    }
    const report = reportFixture({
      components: [{ ...healthyComponent, component: 'openbank-admin-ui', evidence: [failedEvidence] }],
      totals: { failingEvidence: 1 },
      clientExperiences: [{
        id: 'admin-ui', title: 'Admin UI web', surface: 'web', platforms: ['web'],
        evidence: [failedEvidence],
        rum: {
          state: 'passed', policy: 'authenticated', detail: 'Runtime arrival is healthy.',
          observedAt: '2026-09-01T00:00:00.000Z',
        },
        blocker: null,
      }],
    })

    render(<TestIntelligenceFlow report={report} />)

    const health = screen.getByText('NEEDS ATTENTION').closest('.ti-health')
    expect(health).toHaveTextContent(/NEEDS ATTENTION\s*1\s*signal to inspect/)
  })

  it('keeps a component-backed skipped Admin UI suite visible exactly once', () => {
    const skippedEvidence = {
      kind: 'e2e' as const, state: 'skipped' as const, observedAt: '2026-09-01T00:00:00.000Z',
      source: 'openbank-admin-ui CI', environment: 'ci',
    }
    const report = reportFixture({
      components: [{ ...healthyComponent, component: 'openbank-admin-ui', evidence: [skippedEvidence] }],
      clientExperiences: [{
        id: 'admin-ui', title: 'Admin UI web', surface: 'web', platforms: ['web'],
        evidence: [skippedEvidence],
        rum: {
          state: 'passed', policy: 'authenticated', detail: 'Runtime arrival is healthy.',
          observedAt: '2026-09-01T00:00:00.000Z',
        },
        blocker: null,
      }],
    })

    render(<TestIntelligenceFlow report={report} />)

    const health = screen.getByText('NEEDS ATTENTION').closest('.ti-health')
    expect(health).toHaveTextContent(/NEEDS ATTENTION\s*1\s*signal to inspect/)
  })

  it('keeps absent client CI and unresolved client RUM as separate attention signals', () => {
    const report = reportFixture({
      clientExperiences: [{
        id: 'openbank-app', title: 'OpenBank app', surface: 'mobile', platforms: ['android', 'ios'], evidence: [],
        rum: {
          state: 'unknown', policy: 'authenticated', detail: 'No runtime arrival observed.',
          observedAt: null,
        },
        blocker: null,
      }],
    })

    render(<TestIntelligenceFlow report={report} />)

    const health = screen.getByText('NEEDS ATTENTION').closest('.ti-health')
    expect(health).toHaveTextContent(/NEEDS ATTENTION\s*2\s*signals to inspect/)
  })

  it('counts each unresolved client platform without collapsing the matrix to one signal', () => {
    const report = reportFixture({
      clientExperiences: [{
        id: 'openbank-app', title: 'OpenBank app', surface: 'mobile', platforms: ['android', 'ios'],
        evidence: [{
          kind: 'e2e', state: 'passed', observedAt: '2026-09-01T00:00:00.000Z',
          source: 'openbank-app CI', environment: 'ci',
        }],
        rum: {
          state: 'passed', policy: 'consent-gated', detail: 'Generic arrival is healthy.',
          observedAt: '2026-09-01T00:00:00.000Z',
          platforms: [
            { platform: 'android', capability: 'not-run', runtime: 'unknown', detail: 'Exporter incomplete and not attributed.' },
            { platform: 'ios', capability: 'passed', runtime: 'unknown', detail: 'Not attributed.' },
          ],
        },
        blocker: null,
      }],
    })

    render(<TestIntelligenceFlow report={report} />)

    const health = screen.getByText('NEEDS ATTENTION').closest('.ti-health')
    expect(health).toHaveTextContent(/NEEDS ATTENTION\s*2\s*signals to inspect/)
  })

  it('does not hide a stale scheduled RUM audit behind healthy arrival evidence', () => {
    const report = reportFixture({
      clientExperiences: [{
        id: 'admin-ui', title: 'Admin UI web', surface: 'web', platforms: ['web'],
        evidence: [{
          kind: 'e2e', state: 'passed', observedAt: '2026-09-01T00:00:00.000Z',
          source: 'Admin UI CI', environment: 'ci',
        }],
        rum: {
          state: 'passed', policy: 'authenticated', detail: 'Runtime arrival is healthy.',
          observedAt: '2026-09-01T00:00:00.000Z',
          audit: {
            state: 'stale', lastScheduledAt: '2026-08-01T00:00:00.000Z',
            lastSuccessfulAt: '2026-08-01T00:00:00.000Z', freshnessSeconds: 2_678_400,
            detail: 'The scheduled allow-list audit is stale.',
          },
        },
        blocker: null,
      }],
    })

    render(<TestIntelligenceFlow report={report} />)

    const health = screen.getByText('NEEDS ATTENTION').closest('.ti-health')
    expect(health).toHaveTextContent(/NEEDS ATTENTION\s*1\s*signal to inspect/)
  })
})
