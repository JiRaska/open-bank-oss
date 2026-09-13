// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { selectOffersForMarket } from '@/lib/catalog-offer-selection'
import type { Offering } from '@/lib/product-catalog-v2'

const offering = (code: string, market: Offering['market']): Offering => ({
  id: `${code}-id`, specificationId: 'specification-id', code, market, revision: 0,
})

describe('catalog offer selection', () => {
  it('selects only explicit market matches and ranks the most specific offer first', () => {
    const result = selectOffersForMarket([
      offering('GLOBAL', { brands: [], countries: [], channels: [], segments: [], locales: [] }),
      offering('CZ_WEB', { brands: [], countries: ['CZ'], channels: ['WEB'], segments: [], locales: [] }),
      offering('CZ_WEB_EMPLOYEE', { brands: [], countries: ['CZ'], channels: ['WEB'], segments: ['employee'], locales: [] }),
      offering('DE_ONLY', { brands: [], countries: ['DE'], channels: [], segments: [], locales: [] }),
    ], {
      brands: [], countries: ['CZ'], channels: ['WEB'], segments: ['employee'], locales: ['en'],
    })

    expect(result.map(item => item.offering.code)).toEqual(['CZ_WEB_EMPLOYEE', 'CZ_WEB', 'GLOBAL'])
    expect(result[0]).toMatchObject({ specificity: 3, reasons: ['countries:CZ', 'channels:WEB', 'segments:employee'] })
  })

  it('does not widen a private offer when the required context is absent', () => {
    const result = selectOffersForMarket([
      offering('EMPLOYEE', { brands: [], countries: ['CZ'], channels: [], segments: ['employee'], locales: [] }),
    ], { brands: [], countries: ['CZ'], channels: [], segments: [], locales: ['en'] })

    expect(result).toEqual([])
  })
})
