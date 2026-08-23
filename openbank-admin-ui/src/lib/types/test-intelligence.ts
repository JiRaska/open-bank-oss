// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export type EvidenceState =
  | 'passed'
  | 'failed'
  | 'skipped'
  | 'not-run'
  | 'stale'
  | 'blocked'
  | 'unknown'

export type EvidenceKind =
  | 'unit'
  | 'integration'
  | 'contract'
  | 'e2e'
  | 'performance'
  | 'synthetic'
  | 'mutation'
  | 'simulation'

export interface TestCounts {
  discovered: number
  executed: number
  passed: number
  failed: number
  skipped: number
  errors: number
}

export interface CoverageObservation {
  state: EvidenceState
  observedAt: string | null
  lines: { covered: number; missed: number; percentage: number | null }
  branches: { covered: number; missed: number; percentage: number | null }
  source: string | null
}

export interface EvidenceObservation {
  kind: EvidenceKind
  state: EvidenceState
  observedAt: string | null
  source: string
  environment: string
  durationMs?: number
  counts?: TestCounts
  detail?: string
  run?: TestRunProvenance
}

export interface TestRunProvenance {
  id: string
  attempt: number
  commit: string
  branch: string
  workflow: string
  url: string
}

export interface TestInfrastructureObservation {
  resource: 'postgres' | 'redpanda' | 'valkey'
  image: string
  lifecycle: 'started' | 'stopped'
  observedAt: string
}

export interface ComponentTestPosture {
  component: string
  released: boolean
  moneyPath: boolean
  evidence: EvidenceObservation[]
  coverage: CoverageObservation
  testInfrastructure: {
    declared: Array<'postgres' | 'redpanda' | 'valkey'>
    observed: TestInfrastructureObservation[]
  }
}

export interface ContractEvidence {
  consumer: string
  provider: string
  pactFile: string
  state: EvidenceState
  observedAt: string | null
  interactions: number
}

export interface MutationEvidence {
  component: string
  state: EvidenceState
  observedAt: string | null
  total: number
  killed: number
  survived: number
  noCoverage: number
  score: number | null
  run?: TestRunProvenance
}

export interface PerformanceEvidence {
  id: string
  component: string | null
  state: EvidenceState
  observedAt: string | null
  source: string
  thresholds: number
  detail?: string
  run?: TestRunProvenance
}

export interface SyntheticJourneyEvidence {
  id: string
  title: string
  status: 'active' | 'planned'
  state: EvidenceState
  severity: string
  schedule: string | null
  environment: string | null
  covers: string[]
  falsifies: string
  blocker: string | null
  live?: {
    source: 'prometheus'
    observedAt: string
    lastScheduledAt: string | null
    lastSuccessfulAt: string | null
    failuresLast30m: number | null
    freshnessSeconds: number | null
    recentRuns: Array<{ id: string; state: 'passed' | 'failed'; observedAt: string }>
  }
}

export interface TestIntelligenceReport {
  schemaVersion: 1
  collectedAt: string
  components: ComponentTestPosture[]
  contracts: ContractEvidence[]
  mutations: MutationEvidence[]
  performance: PerformanceEvidence[]
  syntheticJourneys: SyntheticJourneyEvidence[]
  history: TestIntelligenceHistoryPoint[]
  runHistory: TestRunHistoryPoint[]
  testCases: TestCaseHistory[]
  totals: {
    components: number
    componentsWithExecutionEvidence: number
    moneyPathComponents: number
    failingEvidence: number
    missingEvidence: number
    staleEvidence: number
  }
  warnings: string[]
}

export interface TestCaseHistory {
  fingerprint: string
  component: string
  kind: Extract<EvidenceKind, 'unit' | 'integration' | 'contract' | 'e2e' | 'simulation'>
  classname: string
  name: string
  owner: string
  state: 'stable' | 'flaky' | 'failing' | 'skipped'
  lastState: 'passed' | 'failed' | 'skipped'
  observations: number
  failureRate: number | null
  averageDurationMs: number
  wastedDurationMs: number
  sameCommitTransitions: number
  lastObservedAt: string
}

export interface TestRunHistoryPoint {
  component: string
  run: TestRunProvenance & { observedAt: string }
  states: Partial<Record<EvidenceKind, EvidenceState>>
  infrastructureStarted: number
  infrastructureStopped: number
}

export interface TestIntelligenceHistoryPoint {
  collectedAt: string
  components: number
  componentsWithExecutionEvidence: number
  failingEvidence: number
  missingEvidence: number
  staleEvidence: number
}

export interface TestAgentFinding {
  id: string
  checkType: string
  severity: 'WARNING' | 'CRITICAL'
  detectedAt: string
  title: string
  component: string
  rootCause: string | null
  proposalUrl: string | null
  status: string
}
