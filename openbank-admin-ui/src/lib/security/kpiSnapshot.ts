// SPDX-License-Identifier: Apache-2.0

import { createEvidenceSnapshotReader, type EvidenceSnapshotReaderOptions } from '../evidence/publishedSnapshot.ts'
import { parseSecurityKpis, type SecurityKpis } from './kpiContract.ts'

export const SECURITY_KPI_MAX_AGE_MS = 8 * 24 * 60 * 60 * 1000
const CLOCK_SKEW_MS = 5 * 60 * 1000

/** Validate the existing weekly publisher contract without manufacturing a new timestamp. */
export function validateSecurityKpiSnapshot(value: unknown, nowMs: number): value is SecurityKpis {
  try {
    const kpis = parseSecurityKpis(value)
    return Date.parse(kpis.generatedAt) <= nowMs + CLOCK_SKEW_MS
  } catch {
    return false
  }
}

export function securityKpiCollectedAt(snapshot: SecurityKpis): string {
  return snapshot.generatedAt
}

export function securityKpiSnapshotBaseUrl(): string | undefined {
  if (process.env.OPENBANK_SECURITY_KPIS_SNAPSHOT_URL !== undefined) {
    return process.env.OPENBANK_SECURITY_KPIS_SNAPSHOT_URL
  }
  const store = process.env.OPENBANK_EVIDENCE_STORE_URL
  return store ? `${store.replace(/\/+$/, '')}/admin-ui/security-kpis` : undefined
}

export interface SecurityKpiSnapshotReaderOptions extends Omit<EvidenceSnapshotReaderOptions<SecurityKpis>, 'baseUrl' | 'validateSnapshot' | 'collectedAt' | 'maxDataAgeMs'> {
  baseUrl?: string
  maxDataAgeMs?: number
}

export function createSecurityKpiSnapshotReader(options: SecurityKpiSnapshotReaderOptions = {}) {
  return createEvidenceSnapshotReader<SecurityKpis>({
    ...options,
    baseUrl: () => options.baseUrl ?? securityKpiSnapshotBaseUrl(),
    validateSnapshot: validateSecurityKpiSnapshot,
    collectedAt: securityKpiCollectedAt,
    maxDataAgeMs: options.maxDataAgeMs ?? SECURITY_KPI_MAX_AGE_MS,
  })
}

/** Shared by the JSON route and its Prometheus exposition so they never read different snapshots. */
export const securityKpiSnapshotReader = createSecurityKpiSnapshotReader()
