// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { parseIncident, parseIncidentList } from '@/lib/security/incidentEvidence'

export const incident = {
  id: '11111111-1111-1111-1111-111111111111',
  title: 'Core banking outage',
  description: 'Payment processing became unavailable.',
  category: 'AVAILABILITY',
  severity: 'P1_CRITICAL',
  status: 'INVESTIGATING',
  affectedServices: ['payment-service'],
  detectedAt: '2026-09-09T08:00:00Z',
  reportedAt: '2026-09-09T08:05:00Z',
  containedAt: null,
  resolvedAt: null,
  rtoMinutes: null,
  rpoMinutes: 0,
  reportedToRegulator: false,
  regulatoryReportId: null,
  assignedTo: 'incident-commander',
  createdAt: '2026-09-09T08:05:00Z',
  updatedAt: '2026-09-09T08:10:00Z',
}

describe('DORA incident evidence', () => {
  it('accepts the complete service contract and discards unknown fields', () => {
    expect(parseIncident({ ...incident, internalNote: 'not for the UI' })).toEqual(incident)
    expect(parseIncidentList([incident])).toEqual([incident])
    expect(parseIncidentList([])).toEqual([])
  })

  it.each([
    ['unknown severity', { severity: 'SEVERE' }],
    ['unknown status', { status: 'DONE' }],
    ['unknown category', { category: 'NETWORK' }],
    ['invalid identity', { id: 'incident-1' }],
    ['invalid timestamp', { detectedAt: 'today' }],
    ['non-RFC3339 timestamp', { detectedAt: '2026-09-09 08:00:00' }],
    ['negative recovery objective', { rtoMinutes: -1 }],
    ['invalid service list', { affectedServices: ['payment-service', ''] }],
    ['duplicate affected service', { affectedServices: ['payment-service', 'payment-service'] }],
    ['oversized title', { title: 'x'.repeat(201) }],
    ['report flag without evidence id', { reportedToRegulator: true }],
    ['report id without report flag', { regulatoryReportId: 'DORA-2026-9' }],
  ])('rejects %s', (_label, change) => {
    expect(parseIncident({ ...incident, ...change })).toBeNull()
  })

  it('rejects the full register when any row is malformed', () => {
    expect(parseIncidentList([incident, { ...incident, id: 'bad' }])).toBeNull()
    expect(parseIncidentList({ incidents: [incident] })).toBeNull()
  })

  it('bounds the verified register and rejects ambiguous duplicate identities', () => {
    expect(parseIncidentList([incident, incident])).toBeNull()
    expect(parseIncidentList(Array.from({ length: 501 }, (_, index) => ({
      ...incident,
      id: `00000000-0000-0000-0000-${String(index).padStart(12, '0')}`,
    })))).toBeNull()
  })
})
