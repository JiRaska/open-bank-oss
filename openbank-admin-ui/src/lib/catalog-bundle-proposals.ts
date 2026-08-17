// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { MarketContext, Offering } from '@/lib/product-catalog-v2'

export interface BundleProposal {
  offering: Offering
  reasons: string[]
  specificity: number
}

const dimensions: Array<keyof MarketContext> = [
  'brands', 'countries', 'channels', 'segments', 'locales',
]

/**
 * Proposes components that are available wherever the source bundle is available.
 * This deliberately uses only catalog market metadata, never customer data or an
 * eligibility decision. The server remains the authority at publication time.
 */
export function proposeBundleComponents(
  source: Offering,
  offerings: Offering[],
  existingTargetIds: string[] = [],
): BundleProposal[] {
  const existing = new Set(existingTargetIds)
  return offerings.flatMap(offering => {
    if (offering.id === source.id || existing.has(offering.id)) return []
    const reasons: string[] = []
    for (const dimension of dimensions) {
      const bundleAudience = source.market[dimension] ?? []
      const componentAudience = offering.market[dimension] ?? []
      if (bundleAudience.length === 0) {
        reasons.push(`${dimension}: bundle is global`)
        continue
      }
      if (componentAudience.length === 0) {
        reasons.push(`${dimension}: component is global`)
        continue
      }
      if (!bundleAudience.every(value => componentAudience.includes(value))) return []
      reasons.push(`${dimension}: compatible scope`)
    }
    const specificity = dimensions.filter(dimension => (offering.market[dimension] ?? []).length > 0).length
    return [{ offering, reasons, specificity }]
  }).sort((left, right) => right.specificity - left.specificity || left.offering.code.localeCompare(right.offering.code))
}
