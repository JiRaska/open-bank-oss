// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseSecurityKpis } from '@/lib/security/kpiContract'
import { SECURITY_KPI_MAX_AGE_MS, securityKpiCollectedAt, securityKpiSnapshotBaseUrl, validateSecurityKpiSnapshot } from '@/lib/security/kpiSnapshot'

const snapshot = {
  generatedAt: '2026-09-10T02:00:00Z',
  netpol: { available: true, covered: 8, total: 10, coveragePct: 80, gateGreen: true },
  freshness: { available: true, fleetScore: 75, scoredModules: 12, unknownModules: 2 },
  credentials: { available: true, totalSecrets: 12, staticSecrets: 10, withDeadline: 6, overdue: 1, undeclared: 3, overdueFound: true },
  fuzz: { available: true, inScope: 4, tested: 3, coveragePct: 75, totalExercised: 120, excludedCount: 1, runDate: '2026-09-09' },
  threatModels: { available: true, moneyPathTotal: 6, withModel: 5, staleCount: 2, oldestDays: 110 },
  mttr: { available: true, fixedCount: 8, medianFixDays: 2.5, openCount: 1, oldestOpenDays: 4 },
}

describe('security KPI evidence contract', () => {
  it('accepts a complete internally consistent snapshot', () => {
    expect(parseSecurityKpis(snapshot)).toEqual(snapshot)
  })

  it.each([
    ['network coverage', { netpol: { ...snapshot.netpol, coveragePct: 90 } }],
    ['credential inventory', { credentials: { ...snapshot.credentials, undeclared: 4 } }],
    ['credential verdict', { credentials: { ...snapshot.credentials, overdueFound: false } }],
    ['fuzz coverage', { fuzz: { ...snapshot.fuzz, tested: 4 } }],
    ['threat-model totals', { threatModels: { ...snapshot.threatModels, staleCount: 6 } }],
  ])('rejects contradictory %s evidence', (_label, override) => {
    expect(() => parseSecurityKpis({ ...snapshot, ...override })).toThrow('Contradictory')
  })

  it.each([
    ['future-shaped percentage', { freshness: { ...snapshot.freshness, fleetScore: 101 } }],
    ['negative count', { mttr: { ...snapshot.mttr, openCount: -1 } }],
    ['non-finite duration', { mttr: { ...snapshot.mttr, medianFixDays: Number.NaN } }],
    ['invalid timestamp', { generatedAt: 'today' }],
  ])('rejects %s rather than exporting a metric', (_label, override) => {
    expect(() => parseSecurityKpis({ ...snapshot, ...override })).toThrow()
  })

  it('preserves a degraded collector without inventing zero values', () => {
    const result = parseSecurityKpis({ ...snapshot, mttr: { available: false, reason: 'source unavailable' } })
    expect(result.mttr).toEqual({ available: false, reason: 'source unavailable' })
  })

  it('uses the original generatedAt as the published evidence timestamp', () => {
    expect(validateSecurityKpiSnapshot(snapshot, Date.parse('2026-09-10T02:01:00Z'))).toBe(true)
    expect(securityKpiCollectedAt(parseSecurityKpis(snapshot))).toBe(snapshot.generatedAt)
    expect(SECURITY_KPI_MAX_AGE_MS).toBe(8 * 24 * 60 * 60 * 1000)
    expect(validateSecurityKpiSnapshot(snapshot, Date.parse('2026-09-10T01:54:59Z'))).toBe(false)
  })

  it('derives the independent dataset URL from the shared evidence store', () => {
    process.env.OPENBANK_EVIDENCE_STORE_URL = 'https://evidence.example/store/'
    expect(securityKpiSnapshotBaseUrl()).toBe('https://evidence.example/store/admin-ui/security-kpis')
    process.env.OPENBANK_SECURITY_KPIS_SNAPSHOT_URL = 'https://override.example/kpis'
    expect(securityKpiSnapshotBaseUrl()).toBe('https://override.example/kpis')
    delete process.env.OPENBANK_EVIDENCE_STORE_URL
    delete process.env.OPENBANK_SECURITY_KPIS_SNAPSHOT_URL
  })
})
