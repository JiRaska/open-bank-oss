// SPDX-License-Identifier: Apache-2.0

/** The publisher runs weekly; allow one day for its PR/build/deploy path to complete. */
export const SECURITY_KPI_MAX_AGE_MS = 8 * 24 * 60 * 60 * 1000

export type SecurityKpiFreshness = 'fresh' | 'stale'

export function classifySecurityKpiFreshness(generatedAt: string, nowMs = Date.now()): SecurityKpiFreshness {
  const generatedAtMs = Date.parse(generatedAt)
  if (!Number.isFinite(generatedAtMs) || generatedAtMs > nowMs + 5 * 60 * 1000) return 'stale'
  return nowMs - generatedAtMs >= SECURITY_KPI_MAX_AGE_MS ? 'stale' : 'fresh'
}
