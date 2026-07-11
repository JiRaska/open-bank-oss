// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pure security-report aggregation, extracted from the Security Scanner page so
// the "unreachable service is not a vulnerability" rule is unit-testable rather
// than living only in JSX. An unreachable service (typically scaled to zero) has
// NO known findings — counting it as a failure, or letting its upstream F-grade
// drag the platform score, is a false positive. These helpers compute the
// headline numbers over REACHABLE services only and surface the unreachable ones
// as a coverage gap.

export interface ScanFindingLike { severity: string }
export interface ScanResultLike {
  reachable: boolean
  score: number
  grade: string
  findings: ScanFindingLike[]
}

// Standard letter-grade bands, used to derive the platform grade from the score
// recomputed over only the reachable services.
export function gradeFromScore(score: number): string {
  if (score >= 95) return 'A+'
  if (score >= 90) return 'A'
  if (score >= 80) return 'B'
  if (score >= 70) return 'C'
  if (score >= 60) return 'D'
  return 'F'
}

export interface ReachableSummary {
  reachableCount: number
  unreachableCount: number
  criticalCount: number
  highCount: number
  avgScore: number
  platformGrade: string
}

export function summarizeReachable(results: ScanResultLike[]): ReachableSummary {
  const reachable = results.filter(r => r.reachable)
  const countSeverity = (sev: string) =>
    reachable.reduce((n, r) => n + r.findings.filter(f => f.severity === sev).length, 0)
  const avgScore = reachable.length
    ? Math.round(reachable.reduce((s, r) => s + r.score, 0) / reachable.length)
    : 0
  return {
    reachableCount: reachable.length,
    unreachableCount: results.length - reachable.length,
    criticalCount: countSeverity('CRITICAL'),
    highCount: countSeverity('HIGH'),
    avgScore,
    platformGrade: reachable.length ? gradeFromScore(avgScore) : 'N/A',
  }
}

export type ServiceVerdict = 'not_scanned' | 'fail' | 'pass' | 'review'

// The per-row status pill. Unreachable → "not_scanned" (neutral), NEVER "fail" —
// that mis-mapping was the reported false positive.
export function serviceVerdict(r: { reachable: boolean; grade: string }): ServiceVerdict {
  if (!r.reachable) return 'not_scanned'
  if (['F', 'D', 'C'].includes(r.grade)) return 'fail'
  if (['A+', 'A'].includes(r.grade)) return 'pass'
  return 'review'
}
