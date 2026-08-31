// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect } from 'vitest'
import { buildCnbTrend, defaultTrendWindow } from '@/lib/fx/trend'

// The three-calendar-month CNB reference-mid trend (issue #7735). These tests mirror the
// windowing and normalization semantics asserted server-side in customer-edge's
// CustomerEdgeResourceTest.kt (mapCnbTrend) so admin-ui and the customer app cannot silently
// drift — same date-window rule, same dedup/order/inverse-pair rules.

describe('defaultTrendWindow', () => {
  it('spans exactly three calendar months by date, not a row-count approximation', () => {
    const to = new Date('2026-06-15T12:00:00.000Z')
    const { from, to: toOut } = defaultTrendWindow(to)
    expect(toOut).toBe(to.toISOString())
    expect(from).toBe('2026-03-15T12:00:00.000Z')
  })
})

describe('buildCnbTrend', () => {
  it('orders chronologically oldest first and marks indicative', () => {
    const rows = [
      { baseCurrency: 'EUR', quoteCurrency: 'CZK', midRate: '25.10', validFrom: '2026-06-15T00:00:00Z' },
      { baseCurrency: 'EUR', quoteCurrency: 'CZK', midRate: '25.00', validFrom: '2026-06-14T00:00:00Z' },
    ]
    const trend = buildCnbTrend(rows, 'EUR', 'CZK', false)
    expect(trend.indicative).toBe(true)
    expect(trend.points.map(p => p.date)).toEqual(['2026-06-14', '2026-06-15'])
  })

  it('dedupes same-day observations keeping the latest timestamp', () => {
    const rows = [
      { baseCurrency: 'EUR', quoteCurrency: 'CZK', midRate: '25.00', validFrom: '2026-06-14T08:00:00Z' },
      { baseCurrency: 'EUR', quoteCurrency: 'CZK', midRate: '25.20', validFrom: '2026-06-14T14:30:00Z' },
    ]
    const trend = buildCnbTrend(rows, 'EUR', 'CZK', false)
    expect(trend.points).toHaveLength(1)
    expect(trend.points[0].rate).toBe('25.2')
  })

  it('excludes rows with no usable rate, no timestamp, zero or negative rate', () => {
    const rows = [
      { baseCurrency: 'EUR', quoteCurrency: 'CZK', validFrom: '2026-06-14T00:00:00Z' }, // no rate
      { baseCurrency: 'EUR', quoteCurrency: 'CZK', midRate: '25.00' }, // no timestamp
      { baseCurrency: 'EUR', quoteCurrency: 'CZK', midRate: '0', validFrom: '2026-06-15T00:00:00Z' },
      { baseCurrency: 'EUR', quoteCurrency: 'CZK', midRate: '-1', validFrom: '2026-06-16T00:00:00Z' },
      { baseCurrency: 'EUR', quoteCurrency: 'CZK', midRate: '25.00', validFrom: '2026-06-17T00:00:00Z' },
    ]
    const trend = buildCnbTrend(rows, 'EUR', 'CZK', false)
    expect(trend.points).toHaveLength(1)
    expect(trend.points[0].date).toBe('2026-06-17')
  })

  it('excludes rows for a different pair', () => {
    const rows = [
      { baseCurrency: 'USD', quoteCurrency: 'CZK', midRate: '22.00', validFrom: '2026-06-14T00:00:00Z' },
      { baseCurrency: 'EUR', quoteCurrency: 'CZK', midRate: '25.00', validFrom: '2026-06-15T00:00:00Z' },
    ]
    const trend = buildCnbTrend(rows, 'EUR', 'CZK', false)
    expect(trend.points).toHaveLength(1)
    expect(trend.points[0].date).toBe('2026-06-15')
  })

  it('inverted mode inverts every rate and reports the originally requested pair', () => {
    const rows = [{ baseCurrency: 'EUR', quoteCurrency: 'CZK', midRate: '25.00', validFrom: '2026-06-14T00:00:00Z' }]
    const trend = buildCnbTrend(rows, 'CZK', 'EUR', true)
    expect(trend.base).toBe('CZK')
    expect(trend.quote).toBe('EUR')
    expect(trend.points[0].rate).toBe(String(1 / 25))
  })

  it('returns an empty, still-indicative trend for no rows', () => {
    const trend = buildCnbTrend([], 'EUR', 'CZK', false)
    expect(trend.indicative).toBe(true)
    expect(trend.points).toEqual([])
  })
})
