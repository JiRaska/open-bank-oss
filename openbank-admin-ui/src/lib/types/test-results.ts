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
}
