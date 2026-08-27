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
  | 'visual'
  | 'trace'
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
  diagnostics?: TestDiagnosticArtifact[]
}

export interface TestDiagnosticArtifact {
  kind: 'playwright-report'
  name: string
  url: string
  retentionDays: 7
  access: 'github-run-authenticated'
  mayContainSensitiveData: true
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
  /** Why a broker verdict is unavailable, or the authority behind one that is. */
  verificationDetail?: string
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
  metrics?: {
    p95Ms: number | null
    errorRatePercent: number | null
    checkPassRatePercent: number | null
    requests: number | null
  }
  detail?: string
  run?: TestRunProvenance
  plan?: {
    executionMode: string
    safetyBoundary: string
    targetSchedule: string | null
    baselineReport: string | null
    blocker: string | null
  }
}

export interface SyntheticJourneyEvidence {
  id: string
  title: string
  capability?: string
  status: 'active' | 'planned'
  state: EvidenceState
  severity: string
  executor?: 'kubernetes-cronjob' | 'github-actions'
  schedule: string | null
  environment: string | null
  covers: string[]
  falsifies: string
  blocker: string | null
  /** Known prerequisite for an active journey. It explains a current signal but never changes its verdict. */
  runtimeNote?: string | null
  ci?: {
    state: EvidenceState
    observedAt: string
    detail: string
    run: TestRunProvenance
  }
  live?: {
    source: 'prometheus'
    observedAt: string
    lastScheduledAt: string | null
    lastSuccessfulAt: string | null
    failuresWithinWindow: number | null
    failureWindowSeconds: number
    activeJobs: number | null
    freshnessSeconds: number | null
    recentRuns: Array<{ id: string; state: 'passed' | 'failed'; observedAt: string }>
    /**
     * k6 remote-write measurements from the same journey window.  They are supplementary
     * performance evidence only: the CronJob outcome remains the availability verdict.
     */
    performance: {
      source: 'prometheus'
      windowSeconds: number
      worstP95Ms: number | null
      worstCheckPassRatePercent: number | null
    }
  }
}

export interface JourneyCoverageSummary {
  moneyPathTotal: number
  activelyCovered: number
  explicitlyUnwatched: number
  services: Array<{
    component: string
    state: 'covered' | 'unwatched'
    journeys: string[]
    reason: string | null
  }>
}

/**
 * Evidence emitted by a customer or operator client repository.  It is intentionally
 * separate from a deployable backend component: a mobile build can be healthy while
 * the corresponding runtime RUM signal is absent (and vice versa).
 */
export interface ClientExperienceEvidence {
  id: 'admin-ui' | 'openbank-app'
  title: string
  surface: 'web' | 'mobile'
  platforms: string[]
  evidence: EvidenceObservation[]
  rum: {
    state: EvidenceState
    policy: 'not-applicable' | 'rejected' | 'consent-gated'
    detail: string
    observedAt: string | null
    source?: 'prometheus' | 'tempo' | null
    sampledSpansLast7d?: number | null
    errorSpansLast7d?: number | null
    /** A bounded inspection of sampled mobile traces.  This proves trace-context
     * continuity only for the listed sampled traces; it is never a traffic estimate. */
    backendCorrelations?: {
      inspectedTraces: number
      correlatedTraces: number
      backendServices: string[]
      truncated: boolean
    } | null
    /** Capability and runtime attribution are deliberately separate: an untagged mobile span
     * proves arrival, never which OS emitted it. */
    platforms?: Array<{
      platform: 'android' | 'ios'
      capability: EvidenceState
      runtime: EvidenceState
      detail: string
    }>
  }
  blocker: string | null
}

export interface TestIntelligenceReport {
  schemaVersion: 1
  collectedAt: string
  components: ComponentTestPosture[]
  contracts: ContractEvidence[]
  mutations: MutationEvidence[]
  performance: PerformanceEvidence[]
  syntheticJourneys: SyntheticJourneyEvidence[]
  journeyCoverage?: JourneyCoverageSummary
  clientExperiences: ClientExperienceEvidence[]
  history: TestIntelligenceHistoryPoint[]
  runHistory: TestRunHistoryPoint[]
  testCases: TestCaseHistory[]
  testImpact?: TestImpactEvidence
  totals: {
    components: number
    componentsWithExecutionEvidence: number
    moneyPathComponents: number
    failingEvidence: number
    missingEvidence: number
    staleEvidence: number
    unknownEvidence?: number
    unresolvedEvidence?: number
  }
  warnings: string[]
}

/**
 * Impact selection is deliberately a separate, bounded evidence surface. `unknown` means no
 * verified test-to-production map was collected; it never means a test is unaffected.
 */
export interface TestImpactEvidence {
  schemaVersion: 1
  mode: 'shadow'
  mappingState: 'unknown'
  selectionState: 'unavailable'
  declaredByAllRetainedRuns: boolean
  detail: string
}

export interface TestCaseHistory {
  fingerprint: string
  component: string
  kind: Extract<EvidenceKind, 'unit' | 'integration' | 'contract' | 'e2e' | 'simulation'>
  classname: string
  name: string
  /** Verified path to the test definition; never a claimed production dependency. */
  testDefinitionPath?: string | null
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
  unknownEvidence?: number
  unresolvedEvidence?: number
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
