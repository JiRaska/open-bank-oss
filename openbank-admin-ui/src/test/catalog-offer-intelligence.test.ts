// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { explainOfferSelection, simulateBundleImpact } from '@/lib/catalog-offer-intelligence'
import { selectOffersForMarket } from '@/lib/catalog-offer-selection'
import type { Offering } from '@/lib/product-catalog-v2'

const offer = (id: string, code: string, market: Offering['market']): Offering => ({
  id, code, specificationId: 'specification-id', market, revision: 1,
})

const context = { brands: [], countries: ['CZ'], channels: ['WEB'], segments: ['employee'], locales: ['en'] }

describe('catalog offer intelligence', () => {
  it('simulates a bundle only from market visibility and never invents a price decision', () => {
    const bundle = offer('bundle', 'BUNDLE', { brands: [], countries: ['CZ'], channels: ['WEB'], segments: ['employee'], locales: [] })
    const component = offer('component', 'COMPONENT', { brands: [], countries: ['CZ'], channels: [], segments: [], locales: [] })

    const result = simulateBundleImpact(bundle, component, context, 'en')

    expect(result).toMatchObject({ sourceVisible: true, componentVisible: true })
    expect(result.summary).toContain('does not change prices')
    expect(result.trace).toContain('countries:CZ')
  })

  it('can explain an authorized match but gives an unmatched private offer no input path', () => {
    const publicOffer = offer('public', 'PUBLIC', { brands: [], countries: ['CZ'], channels: [], segments: [], locales: [] })
    const privateOffer = offer('private', 'PRIVATE_EMPLOYEE', { brands: [], countries: ['CZ'], channels: [], segments: ['employee'], locales: [] })
    const unentitledContext = { ...context, segments: [] }
    const selections = selectOffersForMarket([publicOffer, privateOffer], unentitledContext)

    expect(selections.map(item => item.offering.id)).toEqual(['public'])
    const explanation = explainOfferSelection(selections[0], 'en')
    expect(JSON.stringify(explanation)).not.toContain('PRIVATE_EMPLOYEE')
    expect(explanation.privacyNotice).toContain('never reveals unmatched private offers')
  })
})
