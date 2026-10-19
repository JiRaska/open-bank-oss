// SPDX-License-Identifier: Apache-2.0
import type { EvidenceState, TestIntelligenceReport } from '../types/test-intelligence'

const SHA = /^[0-9a-f]{40}$/
const COMPONENT = /^openbank-[a-z0-9-]{2,60}$/
const STATES = new Set<EvidenceState>(['passed', 'failed', 'skipped', 'not-run', 'stale', 'blocked', 'unknown'])
const KINDS = new Set(['unit', 'integration', 'contract', 'e2e', 'trace', 'mutation', 'simulation', 'performance', 'synthetic', 'visual'])

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function nonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function validObservedState(value: unknown): boolean {
  return record(value) && typeof value.kind === 'string' && KINDS.has(value.kind)
    && typeof value.state === 'string' && STATES.has(value.state as EvidenceState)
    && (value.observedAt === null || (typeof value.observedAt === 'string' && Number.isFinite(Date.parse(value.observedAt))))
}

function validCoverage(value: unknown): boolean {
  if (!record(value) || typeof value.state !== 'string' || !STATES.has(value.state as EvidenceState)
      || !record(value.lines) || !record(value.branches)) return false
  const metric = (item: Record<string, unknown>) => nonNegativeInteger(item.covered)
    && nonNegativeInteger(item.missed)
    && (item.percentage === null || (typeof item.percentage === 'number' && Number.isFinite(item.percentage)
      && item.percentage >= 0 && item.percentage <= 100))
  return metric(value.lines) && metric(value.branches)
    && (value.observedAt === null || typeof value.observedAt === 'string' && Number.isFinite(Date.parse(value.observedAt)))
    && (value.source === null || typeof value.source === 'string')
}

/**
 * Boundary for the remotely published fleet snapshot. This intentionally validates shape and
 * roster entries without pinning the roster to a code-side list: the published dataset is the
 * source that lets a newly released component appear without an Admin UI image rebuild.
 */
export function validateTestIntelligenceSnapshot(value: unknown, nowMs: number): value is TestIntelligenceReport {
  if (!record(value) || value.schemaVersion !== 1
      || typeof value.collectedAt !== 'string' || !Number.isFinite(Date.parse(value.collectedAt))
      || Date.parse(value.collectedAt) > nowMs + 5 * 60_000
      || !Array.isArray(value.components) || !Array.isArray(value.contracts)
      || !Array.isArray(value.mutations) || !Array.isArray(value.performance)
      || !Array.isArray(value.performanceHistory) || !Array.isArray(value.syntheticJourneys)
      || !Array.isArray(value.clientExperiences) || !Array.isArray(value.history)
      || !Array.isArray(value.runHistory) || !Array.isArray(value.testCases)
      || !Array.isArray(value.warnings) || !value.warnings.every(item => typeof item === 'string')
      || !record(value.totals)) return false

  const componentNames = new Set<string>()
  for (const component of value.components) {
    if (!record(component) || typeof component.component !== 'string' || !COMPONENT.test(component.component)
        || componentNames.has(component.component) || typeof component.released !== 'boolean'
        || typeof component.moneyPath !== 'boolean' || !Array.isArray(component.evidence)
        || !component.evidence.every(validObservedState) || !validCoverage(component.coverage)
        || !record(component.testInfrastructure) || !Array.isArray(component.testInfrastructure.declared)
        || !Array.isArray(component.testInfrastructure.observed)) return false
    componentNames.add(component.component)
  }

  const totals = value.totals
  const validTotals = ['components', 'componentsWithExecutionEvidence', 'moneyPathComponents', 'failingEvidence',
    'missingEvidence', 'staleEvidence'].every(key => nonNegativeInteger(totals[key]))
  return validTotals && totals.components === componentNames.size
    && totals.moneyPathComponents === value.components.filter(component => record(component) && component.moneyPath === true).length
}

export function testIntelligenceCollectedAt(snapshot: TestIntelligenceReport): string {
  return snapshot.collectedAt
}

export function testIntelligenceSnapshotBaseUrl(): string | undefined {
  if (process.env.OPENBANK_TEST_INTELLIGENCE_SNAPSHOT_URL !== undefined) {
    return process.env.OPENBANK_TEST_INTELLIGENCE_SNAPSHOT_URL
  }
  const base = process.env.OPENBANK_EVIDENCE_STORE_URL
  if (!base) return undefined
  return `${base.replace(/\/+$/, '')}/admin-ui/test-intelligence`
}
