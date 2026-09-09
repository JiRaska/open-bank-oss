// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readIncidentEnvelope } from '@/lib/security/incidentRegister'

const incident = {
  id: 'ict-42', title: 'Payments unavailable', severity: 'HIGH', status: 'OPEN', category: 'AVAILABILITY',
  detectedAt: '2026-09-09T00:00:00Z', affectedServices: ['payments'], reportedToRegulator: false,
}

describe('incident register response boundary', () => {
  it('accepts a complete, valid register', async () => {
    await expect(readIncidentEnvelope(new Response(JSON.stringify({ available: true, incidents: [incident] })))).resolves.toEqual({ available: true, incidents: [incident] })
  })

  it.each([
    { ...incident, detectedAt: 'not-a-date' },
    { ...incident, affectedServices: [42] },
    { ...incident, reportedToRegulator: 'no' },
  ])('rejects malformed incident evidence', async malformed => {
    await expect(readIncidentEnvelope(new Response(JSON.stringify({ available: true, incidents: [malformed] })))).rejects.toThrow('invalid incident register payload')
  })

  it('rejects an HTTP failure even when its body resembles a register', async () => {
    await expect(readIncidentEnvelope(new Response(JSON.stringify({ available: true, incidents: [] }), { status: 500 }))).rejects.toThrow('HTTP 500')
  })

  it('normalizes an unknown unavailable reason without inventing an empty register', async () => {
    await expect(readIncidentEnvelope(new Response(JSON.stringify({ available: false, reason: 'surprise' })))).resolves.toEqual({ available: false, reason: 'error' })
  })
})
