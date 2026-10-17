// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Shared types for the /api/test-results routes and their UI consumers.
// Kept out of the route.ts files because Next.js App Router generates
// special d.ts wrappers around route handlers — importing types from
// route modules works locally with cached state but fails in clean
// Docker builds. A plain module avoids that whole class of problem.

export interface TypeBreakdown {
  tests: number
  passed: number
  failed: number
}

export interface ServiceTestResult {
  service: string
  tests: number
  passed: number
  failed: number
  skipped: number
  errors: number
  durationMs: number
  lastRunAt: string | null
  testFiles: number
  unit: TypeBreakdown
  integration: TypeBreakdown
}

export interface TestResultsResponse {
  services: ServiceTestResult[]
  totals: {
    tests: number
    passed: number
    failed: number
    skipped: number
    services: number
    servicesWithTests: number
    unit: TypeBreakdown
    integration: TypeBreakdown
  }
  collectedAt: string
  error?: string
  evidence?: TestEvidenceMeta
}

/** Provenance of a test-evidence payload: where it came from and how old it is. */
export interface TestEvidenceMeta {
  /** `live` = fetched now from the CI-published URL; `bundled` = baked into the image. */
  source: 'live' | 'bundled'
  /** When the underlying data was produced (null if unknown — never invented). */
  asOf: string | null
  ageSeconds: number | null
  stale: boolean
  /** Why the live source was not used (bundled fallback only). */
  liveError?: string
  /** Count of test FILES in the repo at image build; compare with tests that reported. */
  inventory: TestInventory | null
}

export interface TestInventory {
  collectedAt: string
  kotlin: { unit: number; integration: number }
  adminUi: number
  e2e: number
  totalFiles: number
}
