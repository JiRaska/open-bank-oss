// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { proposeBundleComponents } from '@/lib/catalog-bundle-proposals'
import type { Offering } from '@/lib/product-catalog-v2'

const market = (countries: string[] = [], channels: string[] = [], segments: string[] = []) => ({
  brands: [], countries, channels, segments, locales: [],
})
const offer = (id: string, code: string, value = market()): Offering => ({
  id, code, specificationId: 'spec', market: value, revision: 1,
})

describe('proposeBundleComponents', () => {
  it('suggests global and wider-scoped components with an explanation', () => {
    const source = offer('bundle', 'CZ_WEB_EMPLOYEE', market(['CZ'], ['WEB'], ['employee']))
    const proposals = proposeBundleComponents(source, [
      source,
      offer('global', 'GLOBAL'),
      offer('web', 'CZ_WEB', market(['CZ'], ['WEB'])),
      offer('wrong-country', 'DE_WEB', market(['DE'], ['WEB'])),
    ])

    expect(proposals.map(proposal => proposal.offering.code)).toEqual(['CZ_WEB', 'GLOBAL'])
    expect(proposals[0].reasons).toContain('countries: compatible scope')
    expect(proposals[0].reasons).toContain('segments: component is global')
  })

  it('does not re-propose an existing relationship', () => {
    const source = offer('bundle', 'CZ_WEB', market(['CZ'], ['WEB']))
    expect(proposeBundleComponents(source, [offer('component', 'GLOBAL')], ['component'])).toEqual([])
  })
})
