// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { MarketContext, Offering } from '@/lib/product-catalog-v2'

const dimensions = ['brands', 'countries', 'channels', 'segments', 'locales'] as const

type MarketDimension = typeof dimensions[number]

export interface OfferSelection {
  offering: Offering
  /** More constrained matching offers rank before broad offers; ties are stable by code. */
  specificity: number
  reasons: string[]
}

/**
 * Deterministic availability preview for Product Studio.
 *
 * It deliberately receives no customer identifier or behavioural data. A restricted offer is
 * included only when every restriction has an explicit matching market value, avoiding both
 * private-offer leakage and an implicit "all customers" fallback.
 */
export function selectOffersForMarket(
  offerings: Offering[],
  context: MarketContext,
): OfferSelection[] {
  return offerings.flatMap(offering => {
    const reasons: string[] = []
    let specificity = 0

    for (const dimension of dimensions) {
      const restrictions = offering.market[dimension] ?? []
      if (!restrictions.length) continue

      const supplied = context[dimension] ?? []
      const matched = supplied.filter(value => restrictions.includes(value))
      if (!matched.length) return []

      specificity += 1
      reasons.push(`${dimension}:${matched.join(', ')}`)
    }

    if (!reasons.length) reasons.push('global')
    return [{ offering, specificity, reasons }]
  }).sort((left, right) =>
    right.specificity - left.specificity ||
    left.offering.code.localeCompare(right.offering.code) ||
    left.offering.id.localeCompare(right.offering.id),
  )
}
