// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { classifySecurityReportFreshness, isCurrentSecurityReport, parseSecurityReportTimestamp, SECURITY_REPORT_MAX_AGE_MS } from '@/lib/security/reportFreshness'
import { summarizeReachable } from '@/lib/security/summary'
import { hasSecurityObservations } from '@/lib/security/reportFreshness'

describe('security report freshness', () => {
  const now = Date.parse('2026-10-20T12:00:00Z')

  it('allows the scanner cadence plus a short completion window', () => {
    expect(classifySecurityReportFreshness(new Date(now - SECURITY_REPORT_MAX_AGE_MS + 1).toISOString(), now)).toBe('fresh')
    expect(classifySecurityReportFreshness(new Date(now - SECURITY_REPORT_MAX_AGE_MS).toISOString(), now)).toBe('stale')
  })

  it('treats missing, null, malformed, and future timestamps as unknown', () => {
    for (const generatedAt of [undefined, null, 'not-a-time', '2026-02-31T12:00:00Z', '2026-10-20T12:00:00.001Z']) {
      expect(classifySecurityReportFreshness(generatedAt, now)).toBe('unknown')
    }
    expect(parseSecurityReportTimestamp('2026-02-31T12:00:00Z')).toBeNull()
  })

  it('does not call legacy, stale, unknown-source, or scanner-outage reports current', () => {
    expect(isCurrentSecurityReport('scanner', 'fresh', 'available')).toBe(true)
    expect(isCurrentSecurityReport('ci-bundle', 'fresh', 'available')).toBe(true)
    expect(isCurrentSecurityReport(undefined, undefined, undefined)).toBe(false)
    expect(isCurrentSecurityReport('scanner', 'stale', 'available')).toBe(false)
    expect(isCurrentSecurityReport('ci-bundle', 'fresh', 'unavailable')).toBe(false)
    expect(isCurrentSecurityReport('other', 'fresh', 'available')).toBe(false)
  })

  it('treats a report one millisecond past the freshness limit as stale', () => {
    expect(classifySecurityReportFreshness(new Date(now - SECURITY_REPORT_MAX_AGE_MS - 1).toISOString(), now)).toBe('stale')
  })

  it('does not treat a fresh report with no reachable service observations as healthy', () => {
    const summary = summarizeReachable([{ reachable: false, score: 0, grade: 'F', findings: [] }])
    expect(summary.reachableCount).toBe(0)
    expect(hasSecurityObservations(summary.reachableCount)).toBe(false)
    expect(hasSecurityObservations(1)).toBe(true)
  })
})
