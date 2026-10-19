// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { fleetHealthState, summarizeFleetHealth } from '@/lib/dashboard/fleetHealth'

describe('dashboard fleet-health summary', () => {
  it('reports only facts present in the current health sample', () => {
    expect(summarizeFleetHealth([
      { deployed: true, up: true, latencyMs: 18 },
      { deployed: true, up: false, latencyMs: 42 },
      { deployed: false, up: false, latencyMs: null },
    ])).toEqual({
      total: 3,
      deployed: 2,
      healthy: 1,
      observed: 2,
      unknown: 0,
      unhealthy: 1,
      notDeployed: 1,
      averageHealthCheckLatencyMs: 30,
    })
  })

  it('does not turn an absent latency sample into a made-up value', () => {
    const summary = summarizeFleetHealth([{ deployed: false, up: false, latencyMs: null }])
    expect(summary.averageHealthCheckLatencyMs).toBeNull()
    expect(fleetHealthState(summary)).toBe('unavailable')
  })

  it('distinguishes fully healthy deployed services from a degraded current sample', () => {
    expect(fleetHealthState(summarizeFleetHealth([{ deployed: true, up: true, latencyMs: 8 }]))).toBe('healthy')
    expect(fleetHealthState(summarizeFleetHealth([{ deployed: true, up: false, latencyMs: 8 }]))).toBe('degraded')
  })

  it('keeps unknown deployed services out of unhealthy counts and neutralizes incomplete samples', () => {
    const summary = summarizeFleetHealth([
      { deployed: true, up: true, latencyMs: 8 },
      { deployed: true, up: false, unknown: true, latencyMs: 9 },
    ])
    expect(summary).toMatchObject({ deployed: 2, healthy: 1, observed: 1, unknown: 1, unhealthy: 0 })
    expect(fleetHealthState(summary)).toBe('unavailable')
    expect(fleetHealthState(summarizeFleetHealth([
      { deployed: true, up: true, latencyMs: 8 },
      { deployed: true, up: false, latencyMs: 9 },
    ]))).toBe('degraded')
  })
  it('keeps a confirmed outage visible when another observation is unknown', () => {
    const summary = summarizeFleetHealth([
      { deployed: true, up: false, latencyMs: null },
      { deployed: true, up: false, unknown: true, latencyMs: null },
    ])
    expect(summary).toMatchObject({ observed: 1, unhealthy: 1, unknown: 1 })
    expect(fleetHealthState(summary)).toBe('degraded')
  })

})
