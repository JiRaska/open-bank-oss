// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseAccountPortfolio, parseAmlPortfolio, parseLendingPortfolio } from '@/lib/party/portfolioContract'

describe('Customer 360 portfolio contracts', () => {
  it('uses account cursor evidence instead of presenting a page length as a total', () => {
    expect(parseAccountPortfolio({
      data: [{ status: 'ACTIVE' }, { status: 'BLOCKED' }],
      pagination: { nextCursor: 'next', hasMore: true },
    })).toEqual({ count: 2, lowerBound: true, statuses: ['ACTIVE', 'BLOCKED'] })
  })

  it('treats a full AML window as a lower bound and a shorter one as exact', () => {
    expect(parseAmlPortfolio([{ status: 'OPEN' }, { status: 'OPEN' }], 2)).toEqual({ count: 2, lowerBound: true, statuses: ['OPEN'] })
    expect(parseAmlPortfolio([{ status: 'OPEN' }], 2).lowerBound).toBe(false)
  })

  it('keeps the uncapped party lending list exact', () => {
    expect(parseLendingPortfolio([{ state: 'ASSESSMENT' }])).toEqual({ count: 1, lowerBound: false, statuses: ['ASSESSMENT'] })
  })

  it.each([
    [{ data: [], pagination: { hasMore: 'yes', nextCursor: null } }, parseAccountPortfolio],
    [[{ status: 42 }], parseLendingPortfolio],
    [{ data: [] }, (raw: unknown) => parseAmlPortfolio(raw, 100)],
  ])('rejects malformed owning-service evidence', (raw, parser) => {
    expect(() => parser(raw)).toThrow()
  })
})
