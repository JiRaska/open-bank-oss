// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { decisionSchema, outcomeSchema, policySchema, portfolioSchema, portfolioSummarySchema } from '@/components/lending/risk/contracts'

const summary = { currency: 'CZK', asOf: '2026-09-05', loans: 10, unassessed: 1, stale: 1, demonstration: 0, pending: 0, outstanding: 100000, ecl: 2000, stage23Outstanding: 10000, over90Outstanding: 5000 }

describe('credit-risk response contracts', () => {
  it('accepts valid currency-separated portfolio summaries', () => {
    expect(portfolioSummarySchema.parse([summary, { ...summary, currency: 'EUR' }])).toHaveLength(2)
    expect(portfolioSummarySchema.parse([])).toEqual([])
  })

  it('retains pending releases after every exposure has closed', () => {
    expect(portfolioSummarySchema.safeParse([{ ...summary, loans: 0, unassessed: 0, stale: 0, pending: 1 }]).success).toBe(true)
  })

  it.each([
    { loans: -1 }, { unassessed: 0.5 }, { stale: -1 }, { demonstration: -1 },
    { outstanding: -1 }, { ecl: -1 }, { ecl: Number.NaN }, { outstanding: Number.POSITIVE_INFINITY },
    { currency: 'czk' }, { asOf: '2026-02-30' }, { currency: null },
    { unassessed: 6, stale: 5 }, { demonstration: 11 },
    { stage23Outstanding: 100001 }, { over90Outstanding: 100001 },
  ])('rejects invalid summary values %j', override => {
    expect(portfolioSummarySchema.safeParse([{ ...summary, ...override }]).success).toBe(false)
  })

  it.each([decisionSchema, outcomeSchema, portfolioSchema, portfolioSummarySchema])('rejects wrong list response shapes', schema => {
    for (const malformed of [null, {}, { data: [] }, [null], [{}]]) {
      expect(schema.safeParse(malformed).success).toBe(false)
    }
  })

  it('rejects missing policy and invalid outcome counts', () => {
    expect(policySchema.safeParse({ tables: [] }).success).toBe(false)
    expect(outcomeSchema.safeParse([{ engineOutcome: 'APPROVE', priceBand: null, count: -1 }]).success).toBe(false)
    expect(outcomeSchema.safeParse([{ engineOutcome: 'UNKNOWN', priceBand: null, count: 1 }]).success).toBe(false)
  })
})
