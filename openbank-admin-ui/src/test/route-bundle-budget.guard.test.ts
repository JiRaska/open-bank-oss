// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import {
  MAX_ROUTE_CHUNK_BYTES,
  MAX_TOTAL_ROUTE_CHUNK_BYTES,
  assessRouteChunks,
} from '../../scripts/check-route-bundle-budget.mjs'

describe('route-owned JavaScript budget', () => {
  it('fails closed when build output is missing', () => {
    expect(assessRouteChunks([])).toEqual({
      ok: false,
      total: 0,
      violations: ['no route chunks found'],
    })
  })

  it('rejects both one oversized route and aggregate route growth', () => {
    const oversized = assessRouteChunks([{ route: '/system/tests', bytes: MAX_ROUTE_CHUNK_BYTES + 1 }])
    expect(oversized.ok).toBe(false)
    expect(oversized.violations[0]).toContain('/system/tests')

    const aggregate = assessRouteChunks([
      { route: '/a', bytes: MAX_TOTAL_ROUTE_CHUNK_BYTES / 2 },
      { route: '/b', bytes: MAX_TOTAL_ROUTE_CHUNK_BYTES / 2 + 1 },
    ], { maxRoute: MAX_TOTAL_ROUTE_CHUNK_BYTES, maxTotal: MAX_TOTAL_ROUTE_CHUNK_BYTES })
    expect(aggregate.ok).toBe(false)
    expect(aggregate.violations.at(-1)).toContain('route-owned total')
  })

  it('accepts values exactly on both ceilings', () => {
    expect(assessRouteChunks([
      { route: '/a', bytes: MAX_ROUTE_CHUNK_BYTES },
      { route: '/b', bytes: MAX_TOTAL_ROUTE_CHUNK_BYTES - MAX_ROUTE_CHUNK_BYTES },
    ], { maxRoute: MAX_TOTAL_ROUTE_CHUNK_BYTES, maxTotal: MAX_TOTAL_ROUTE_CHUNK_BYTES }).ok).toBe(true)
  })
})
