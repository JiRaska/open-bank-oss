// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Quality report types — contract test results + mutation scores (ADR-0063).
// Produced by scripts/collect-quality-report.mjs and bundled into the admin-ui image as
// quality-report.json (same pattern as test-results.json).

export interface PactInteractionResult {
  description: string
  status: 'passed' | 'failed' | 'pending'
  failure?: string
}

export interface ContractVerification {
  consumer: string
  provider: string
  pactFile: string
  status: 'passed' | 'failed' | 'pending'
  verifiedAt: string | null
  interactions: PactInteractionResult[]
  /** Why `status` stayed 'pending' — see ContractUnavailableReason in test-intelligence.ts. */
  reasonCode?: 'query-error' | 'no-provider-main-version' | 'pending-verification' | null
  /** Safe-to-render explanation for `reasonCode` — never the broker response body or credentials. */
  detail?: string | null
}

export interface MutationScore {
  service: string
  /** Domain package targeted (e.g. "com.openbank.ledger.domain") */
  targetPackage: string
  totalMutants: number
  killed: number
  survived: number
  noCoverage: number
  /** Percentage 0–100, rounded. null when pitest has not run yet. */
  score: number | null
  reportedAt: string | null
}

export interface ServiceQualityScore {
  service: string
  /** 0–100 — unit + integration pass rate */
  unitScore: number | null
  /** 0–100 — kover line coverage */
  coverageScore: number | null
  /** 0–100 — pitest mutation score on domain layer */
  mutationScore: number | null
  /** 0–100 — all pact contracts passing (100) vs any failing (0) vs no contracts (null) */
  contractScore: number | null
  /** Composite: average of non-null components */
  composite: number | null
}

export interface QualityReport {
  contracts: ContractVerification[]
  mutations: MutationScore[]
  serviceScores: ServiceQualityScore[]
  collectedAt: string
  error?: string
}
