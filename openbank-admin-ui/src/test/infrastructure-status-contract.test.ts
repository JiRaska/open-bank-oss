// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseInfrastructureStatuses } from '@/lib/infra/statusContract'

const status = { id: 'postgres', status: 'UP', latencyMs: 8, checkedAt: '2026-09-09T08:00:00Z' }

describe('infrastructure status response contract', () => {
  it('preserves valid probe evidence keyed by its stable component id', () => {
    expect(parseInfrastructureStatuses({ postgres: status })).toEqual({ postgres: status })
  })

  it.each([
    [{ postgres: { ...status, id: 'kafka' } }, 'entry'],
    [{ postgres: { ...status, status: 'HEALTHY' } }, 'entry'],
    [{ postgres: { ...status, latencyMs: -1 } }, 'latency'],
    [{ postgres: { ...status, checkedAt: 'not-a-date' } }, 'checkedAt'],
    [{}, 'map'],
  ])('rejects malformed probe evidence', (raw, message) => {
    expect(() => parseInfrastructureStatuses(raw)).toThrow(message)
  })
})
