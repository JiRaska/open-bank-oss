// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { MarketContext, RevisionRequest } from '@/lib/product-catalog-v2'

export type MarketContextInput = Record<keyof MarketContext, string>

export const defaultMarketContextInput: MarketContextInput = {
  brands: '', countries: '', channels: '', segments: '', locales: 'en',
}

const splitValues = (value: string, normalize: (entry: string) => string = entry => entry) =>
  [...new Set(value.split(',').map(entry => normalize(entry.trim())).filter(Boolean))]

/** Context keys describe an offer audience. They never contain a customer identifier or personal data. */
export function marketContextFromInput(input: MarketContextInput): MarketContext {
  return {
    brands: splitValues(input.brands),
    countries: splitValues(input.countries, entry => entry.toUpperCase()),
    channels: splitValues(input.channels, entry => entry.toUpperCase()),
    segments: splitValues(input.segments),
    locales: splitValues(input.locales),
  }
}

type Relationship = NonNullable<RevisionRequest['relationships']>[number]

function relationshipsOf(document: Record<string, unknown>): Relationship[] {
  return Array.isArray(document.relationships) ? document.relationships as Relationship[] : []
}

export function addOfferingRelationship(
  document: Record<string, unknown>,
  sourceOfferingId: string,
  relationship: Relationship,
): Record<string, unknown> {
  if (relationship.targetOfferingId === sourceOfferingId) {
    throw new Error('An offer cannot be a component of itself.')
  }
  const relationships = relationshipsOf(document)
  if (relationships.some(item => item.kind === relationship.kind && item.targetOfferingId === relationship.targetOfferingId)) {
    return document
  }
  return { ...document, relationships: [...relationships, relationship] }
}

export function removeOfferingRelationship(
  document: Record<string, unknown>,
  relationship: Relationship,
): Record<string, unknown> {
  return {
    ...document,
    relationships: relationshipsOf(document).filter(
      item => item.kind !== relationship.kind || item.targetOfferingId !== relationship.targetOfferingId,
    ),
  }
}
