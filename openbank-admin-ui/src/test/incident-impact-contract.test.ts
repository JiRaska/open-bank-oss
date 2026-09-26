// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { parseIncidentImpact } from '@/lib/context/incidentImpact'

describe('incident impact contract', () => {
  it('keeps legacy coverage unknown during staged rollout', () => {
    expect(parseIncidentImpact({ affectedByType: {}, total: 0, drilldownAvailable: false }).projectionStatus).toBe('UNKNOWN')
  })

  it.each([
    { affectedByType: {}, total: 0, drilldownAvailable: false, projectionStatus: 'HEALTHY' },
    { affectedByType: {}, total: 0, drilldownAvailable: false, projectionStatus: ['PARTIAL'] },
    { affectedByType: { SERVICE: 1 }, total: 1, drilldownAvailable: false, projectionStatus: 'MISSING' },
    { affectedByType: [], total: 0, drilldownAvailable: false },
    { affectedByType: { SERVICE: 201 }, total: 201, drilldownAvailable: false },
  ])('rejects contradictory or unbounded evidence', value => {
    expect(() => parseIncidentImpact(value)).toThrow()
  })
})
