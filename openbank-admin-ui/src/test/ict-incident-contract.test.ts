// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseIctIncidentEnvelope, summarizeIctIncidents } from '@/lib/security/ictIncidentContract'

const valid = {
  id: '128cdb0e-04ae-4a35-8af1-ccca692ec413',
  title: 'Payment rail unavailable',
  category: 'AVAILABILITY',
  severity: 'P1_CRITICAL',
  status: 'INVESTIGATING',
  detectedAt: '2026-09-10T01:23:45.123Z',
  affectedServices: ['payment-service', 'ledger-service'],
  reportedToRegulator: false,
}

describe('ICT incident evidence contract', () => {
  it('accepts the exact backend lifecycle vocabulary', () => {
    expect(parseIctIncidentEnvelope({ available: true, incidents: [valid] })).toEqual({ available: true, incidents: [valid] })
  })

  it.each([
    ['non-RFC UUID', { ...valid, id: 'incident-1' }],
    ['unknown severity', { ...valid, severity: 'CRITICAL' }],
    ['unknown status', { ...valid, status: 'ACTIVE' }],
    ['unknown category', { ...valid, category: 'CYBER' }],
    ['invalid timestamp', { ...valid, detectedAt: 'yesterday' }],
    ['non-boolean report state', { ...valid, reportedToRegulator: 'false' }],
    ['invalid service identity', { ...valid, affectedServices: ['Ledger Service'] }],
    ['duplicate affected service', { ...valid, affectedServices: ['ledger-service', 'ledger-service'] }],
  ])('rejects %s rather than rendering untrusted evidence', (_label, incident) => {
    expect(() => parseIctIncidentEnvelope({ available: true, incidents: [incident] })).toThrow()
  })

  it('rejects duplicate technical identities', () => {
    expect(() => parseIctIncidentEnvelope({ available: true, incidents: [valid, valid] })).toThrow('Duplicate incident id')
  })

  it('preserves a typed unavailable envelope without mistaking it for an empty register', () => {
    expect(parseIctIncidentEnvelope({ available: false, reason: 'unreachable' })).toEqual({ available: false, reason: 'unreachable' })
  })

  it('derives the executive score from exact open and severity states', () => {
    const incidents = [
      valid,
      { ...valid, id: '4ba97ae7-04aa-4e25-93e2-f50a4ba97fc9', severity: 'P4_LOW' as const, status: 'OPEN' as const },
      { ...valid, id: '82bb0be3-18ad-4c53-bb7c-f6cfd3ea301d', status: 'RESOLVED' as const },
      { ...valid, id: '0dd662c6-8341-47a5-973c-3820309752ba', status: 'CLOSED' as const },
    ]
    const parsed = parseIctIncidentEnvelope({ available: true, incidents })
    expect(parsed.available && summarizeIctIncidents(parsed.incidents)).toEqual({
      open: 2,
      openCritical: 1,
      unreported: 2,
      score: 50,
      status: 'critical',
    })
  })

  it('does not turn an empty verified register into a degraded score', () => {
    expect(summarizeIctIncidents([])).toEqual({ open: 0, openCritical: 0, unreported: 0, score: 100, status: 'ok' })
  })
})
