// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import {
  addOfferingRelationship,
  defaultMarketContextInput,
  marketContextFromInput,
  removeOfferingRelationship,
} from '@/lib/catalog-offer-composition'

describe('catalog offer composition', () => {
  it('turns a private offer audience into normalized market context without a customer identifier', () => {
    expect(marketContextFromInput({
      ...defaultMarketContextInput,
      countries: 'cz, CZ', channels: 'web', segments: 'employee, employee', locales: 'cs-CZ, en',
    })).toEqual({
      brands: [], countries: ['CZ'], channels: ['WEB'], segments: ['employee'], locales: ['cs-CZ', 'en'],
    })
  })

  it('adds and removes a bundle component without allowing a self-reference', () => {
    const component = { kind: 'BUNDLE' as const, targetOfferingId: 'component-offering' }
    const bundle = addOfferingRelationship({}, 'bundle-offering', component)

    expect(bundle.relationships).toEqual([component])
    expect(addOfferingRelationship(bundle, 'bundle-offering', component)).toBe(bundle)
    expect(removeOfferingRelationship(bundle, component).relationships).toEqual([])
    expect(() => addOfferingRelationship({}, 'bundle-offering', {
      kind: 'BUNDLE', targetOfferingId: 'bundle-offering',
    })).toThrow('cannot be a component of itself')
  })
})
