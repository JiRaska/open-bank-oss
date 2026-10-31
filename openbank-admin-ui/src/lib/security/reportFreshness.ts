// SPDX-License-Identifier: Apache-2.0
// The scanner publishes on a 30-minute cadence. Allow another 15 minutes for
// scan completion and report collection before classifying the evidence as stale.
export const SECURITY_REPORT_MAX_AGE_MS = 45 * 60 * 1000

export type SecurityReportFreshness = 'fresh' | 'stale' | 'unknown'
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/

export function isCurrentSecurityReport(source: unknown, freshness: unknown, scannerStatus: unknown): boolean {
  return (source === 'scanner' || source === 'ci-bundle')
    && freshness === 'fresh'
    && scannerStatus === 'available'
}

export function hasSecurityObservations(reachableCount: unknown): boolean {
  return typeof reachableCount === 'number' && Number.isInteger(reachableCount) && reachableCount > 0
}

export function parseSecurityReportTimestamp(value: unknown): number | null {
  if (typeof value !== 'string' || !RFC3339.test(value)) return null
  const datePart = value.slice(0, 10)
  const parsedDate = new Date(`${datePart}T00:00:00.000Z`)
  if (!Number.isFinite(parsedDate.getTime()) || parsedDate.toISOString().slice(0, 10) !== datePart) return null
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : null
}

export function classifySecurityReportFreshness(generatedAt: unknown, nowMs = Date.now()): SecurityReportFreshness {
  const generatedAtMs = parseSecurityReportTimestamp(generatedAt)
  // Future timestamps are unknown rather than fresh; do not mask source clock skew.
  if (generatedAtMs === null || generatedAtMs > nowMs) return 'unknown'
  return nowMs - generatedAtMs >= SECURITY_REPORT_MAX_AGE_MS ? 'stale' : 'fresh'
}
