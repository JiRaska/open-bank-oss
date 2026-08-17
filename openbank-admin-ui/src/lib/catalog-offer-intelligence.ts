// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { MarketContext, Offering } from '@/lib/product-catalog-v2'
import { selectOffersForMarket, type OfferSelection } from '@/lib/catalog-offer-selection'

export type CatalogLanguage = 'cs' | 'en'

export interface BundleImpact {
  sourceVisible: boolean
  componentVisible: boolean
  summary: string
  trace: string[]
}

export interface OfferExplanation {
  title: string
  summary: string
  trace: string[]
  privacyNotice: string
}

/**
 * A deterministic, deliberately narrow bundle simulation. It answers only whether two already
 * known offerings are visible in the supplied business context. It neither prices, ranks people,
 * nor treats an absent private-offer constraint as a match. Publication revalidates the full
 * relationship server-side.
 */
export function simulateBundleImpact(
  source: Offering,
  component: Offering,
  context: MarketContext,
  language: CatalogLanguage,
): BundleImpact {
  const selections = selectOffersForMarket([source, component], context)
  const sourceSelection = selections.find(selection => selection.offering.id === source.id)
  const componentSelection = selections.find(selection => selection.offering.id === component.id)
  const sourceVisible = Boolean(sourceSelection)
  const componentVisible = Boolean(componentSelection)
  const trace = [
    ...(sourceSelection?.reasons ?? []),
    ...(componentSelection?.reasons ?? []),
  ]

  const summary = sourceVisible && componentVisible
    ? language === 'cs'
      ? 'Bundle i komponenta odpovídají simulovanému tržnímu kontextu. Ceny ani způsobilost se zde nemění.'
      : 'The bundle and component match the simulated market context. This does not change prices or eligibility.'
    : language === 'cs'
      ? 'Komponenta se v tomto kontextu nezobrazí. Návrh nelze používat jako příslib dostupnosti.'
      : 'The component is not visible in this context. The proposal is not a promise of availability.'

  return { sourceVisible, componentVisible, summary, trace }
}

/**
 * Turns an already-authorized deterministic selection trace into operator/customer-safe prose.
 * The function accepts a single selection, not the catalog. Therefore an unmatched private offer
 * has no route into either the explanation or its trace.
 */
export function explainOfferSelection(
  selection: OfferSelection,
  language: CatalogLanguage,
): OfferExplanation {
  const isGlobal = selection.specificity === 0
  const summary = isGlobal
    ? language === 'cs'
      ? 'Nabídka nemá omezení pro zadané obchodní dimenze, a proto je v tomto náhledu dostupná.'
      : 'This offering has no restrictions in the supplied business dimensions, so it is available in this preview.'
    : language === 'cs'
      ? `Nabídka odpovídá ${selection.specificity} explicitním obchodním kritériím.`
      : `This offering matches ${selection.specificity} explicit business criteria.`

  return {
    title: language === 'cs' ? `Proč vidíte ${selection.offering.code}` : `Why ${selection.offering.code} is shown`,
    summary,
    trace: selection.reasons,
    privacyNotice: language === 'cs'
      ? 'Vysvětlení používá pouze již shodná obchodní kritéria. Nečte identitu zákazníka ani nezobrazuje neveřejné nabídky bez shody.'
      : 'This explanation uses only already-matched business criteria. It reads no customer identity and never reveals unmatched private offers.',
  }
}
