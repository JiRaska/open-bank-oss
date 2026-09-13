// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect } from 'vitest'
import {
  OWASP_CATEGORIES, parseSecurityEnvelope, summarizeReachable, serviceVerdict, gradeFromScore,
  type ScanResultLike,
} from '@/lib/security/summary'

const contractFinding = {
  id: 'finding-1', category: 'A01_BROKEN_ACCESS_CONTROL', severity: 'HIGH',
  title: 'Missing authorization check', description: 'A protected operation is exposed.',
  remediation: 'Enforce an explicit permission.', cweId: 'CWE-862', cvssScore: 8.1,
  endpoint: '/api/v1/example',
}

const contractService = {
  serviceName: 'example-service', serviceUrl: 'https://example.com',
  scannedAt: '2026-09-10T02:00:00Z', durationMs: 125, reachable: true,
  findings: [contractFinding], score: 90, grade: 'A',
  headersPresent: { 'content-security-policy': true }, openApiAvailable: true,
}

const contractOwasp = Object.fromEntries(
  OWASP_CATEGORIES.map(category => [category, category === contractFinding.category ? 1 : 0]),
)
const contractReport = {
  reportId: 'b6ea92e9-c4b4-48af-88f5-b23705db5ef8', generatedAt: '2026-09-10T02:01:00Z',
  totalServices: 1, reachableServices: 1, serviceResults: [contractService],
  platformScore: 90, platformGrade: 'A', criticalFindings: 0, highFindings: 1,
  owaspCoverage: contractOwasp, complianceStatus: { PSD2_SCA: true, OWASP_TOP10: true },
}

describe('security evidence contract', () => {
  it('accepts the exact scanner report and preserves its typed evidence', () => {
    expect(parseSecurityEnvelope({ available: true, report: contractReport })).toEqual({ available: true, report: contractReport })
  })

  it.each([
    ['service count', { totalServices: 2 }],
    ['reachable count', { reachableServices: 0 }],
    ['finding count', { highFindings: 0 }],
    ['platform grade', { platformGrade: 'B' }],
    ['OWASP count', { owaspCoverage: { ...contractOwasp, A01_BROKEN_ACCESS_CONTROL: 0 } }],
  ])('rejects a contradictory %s instead of scoring it', (_label, override) => {
    expect(() => parseSecurityEnvelope({ available: true, report: { ...contractReport, ...override } })).toThrow('Contradictory')
  })

  it.each([
    ['unknown severity', { ...contractFinding, severity: 'SEVERE' }],
    ['CVSS above its scale', { ...contractFinding, cvssScore: 11 }],
    ['invalid timestamp', { ...contractService, scannedAt: 'today' }],
    ['score outside its scale', { ...contractService, score: 101 }],
    ['grade inconsistent with score', { ...contractService, grade: 'F' }],
  ])('rejects %s', (_label, invalid) => {
    const serviceResult = 'serviceName' in invalid ? invalid : { ...contractService, findings: [invalid] }
    expect(() => parseSecurityEnvelope({
      available: true, report: { ...contractReport, serviceResults: [serviceResult] },
    })).toThrow()
  })

  it('rejects duplicate service evidence', () => {
    expect(() => parseSecurityEnvelope({
      available: true,
      report: { ...contractReport, totalServices: 2, reachableServices: 2, serviceResults: [contractService, contractService] },
    })).toThrow('Duplicate service result')
  })

  it('preserves an unavailable verdict without manufacturing a report', () => {
    expect(parseSecurityEnvelope({ available: false, reason: 'unauthorized' })).toEqual({
      available: false, reason: 'unauthorized', detail: undefined,
    })
  })
})

const svc = (over: Partial<ScanResultLike>): ScanResultLike => ({
  reachable: true, score: 90, grade: 'A', findings: [], ...over,
})
const finding = (severity: string) => ({ severity })

describe('serviceVerdict', () => {
  it('maps an unreachable service to "not_scanned", never "fail"', () => {
    // The reported false positive: an unreachable (scaled-to-zero) service came
    // back graded F and was rendered as a red "Fail".
    expect(serviceVerdict({ reachable: false, grade: 'F' })).toBe('not_scanned')
    expect(serviceVerdict({ reachable: false, grade: 'A+' })).toBe('not_scanned')
  })
  it('maps reachable grades to fail / pass / review', () => {
    expect(serviceVerdict({ reachable: true, grade: 'F' })).toBe('fail')
    expect(serviceVerdict({ reachable: true, grade: 'D' })).toBe('fail')
    expect(serviceVerdict({ reachable: true, grade: 'C' })).toBe('fail')
    expect(serviceVerdict({ reachable: true, grade: 'A+' })).toBe('pass')
    expect(serviceVerdict({ reachable: true, grade: 'A' })).toBe('pass')
    expect(serviceVerdict({ reachable: true, grade: 'B' })).toBe('review')
  })
})

describe('gradeFromScore', () => {
  it('bands scores into letter grades', () => {
    expect(gradeFromScore(100)).toBe('A+')
    expect(gradeFromScore(92)).toBe('A')
    expect(gradeFromScore(85)).toBe('B')
    expect(gradeFromScore(72)).toBe('C')
    expect(gradeFromScore(61)).toBe('D')
    expect(gradeFromScore(40)).toBe('F')
  })
})

describe('summarizeReachable', () => {
  it('excludes unreachable services from counts and score (no false positives)', () => {
    const results: ScanResultLike[] = [
      svc({ reachable: true, score: 90, grade: 'A', findings: [finding('HIGH')] }),
      // Unreachable + graded F with synthetic findings — must NOT be counted.
      svc({ reachable: false, score: 10, grade: 'F', findings: [finding('CRITICAL'), finding('HIGH')] }),
    ]
    const s = summarizeReachable(results)
    expect(s.unreachableCount).toBe(1)
    expect(s.reachableCount).toBe(1)
    expect(s.criticalCount).toBe(0) // the unreachable service's CRITICAL is not counted
    expect(s.highCount).toBe(1)     // only the reachable service's HIGH
    expect(s.avgScore).toBe(90)     // the F(10) does not drag the score
    expect(s.platformGrade).toBe('A')
  })

  it('counts findings across all reachable services and averages their scores', () => {
    const results: ScanResultLike[] = [
      svc({ reachable: true, score: 80, grade: 'B', findings: [finding('CRITICAL'), finding('CRITICAL')] }),
      svc({ reachable: true, score: 100, grade: 'A+', findings: [] }),
    ]
    const s = summarizeReachable(results)
    expect(s.criticalCount).toBe(2)
    expect(s.avgScore).toBe(90)
    expect(s.platformGrade).toBe('A')
  })

  it('reports N/A grade and zero score when nothing is reachable', () => {
    const results: ScanResultLike[] = [
      svc({ reachable: false, grade: 'F', findings: [finding('CRITICAL')] }),
      svc({ reachable: false, grade: 'D', findings: [finding('HIGH')] }),
    ]
    const s = summarizeReachable(results)
    expect(s.reachableCount).toBe(0)
    expect(s.unreachableCount).toBe(2)
    expect(s.criticalCount).toBe(0)
    expect(s.highCount).toBe(0)
    expect(s.avgScore).toBe(0)
    expect(s.platformGrade).toBe('N/A')
  })

  it('handles an empty report', () => {
    const s = summarizeReachable([])
    expect(s).toEqual({ reachableCount: 0, unreachableCount: 0, criticalCount: 0, highCount: 0, avgScore: 0, platformGrade: 'N/A' })
  })
})
