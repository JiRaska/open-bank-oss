// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pins the client-side entitlement mirror against CardService.enforceEntitlements()
// and CardService.getEntitlements(). The point of the mirror is to tell an operator
// "this client already holds 3 of 3 cards" BEFORE they fill in a form; if it drifts
// from the service it starts refusing issues the service would have accepted (or,
// worse, promising ones it will 409).

import { describe, it, expect } from 'vitest'
import {
  UNLIMITED, allowedCardTypes, allowedNetworks, issueBlockers, quotaOf,
} from '@/lib/cards/entitlements'
import { CARD_NETWORKS, type CardEntitlements } from '@/lib/cards/types'

const catalog = (over: Partial<CardEntitlements> = {}): CardEntitlements => ({
  productCode: 'CURRENT_CZK',
  maxCards: 3,
  issued: 1,
  remaining: 2,
  virtualCardAllowed: true,
  singleUseAllowed: true,
  networks: ['VISA', 'MASTERCARD'],
  tiers: [],
  monthlyFeePerCard: 0,
  enabled: true,
  source: 'CATALOG',
  ...over,
})

// What the service answers when product-catalog could not be consulted.
const fallback = (): CardEntitlements => catalog({
  maxCards: UNLIMITED,
  remaining: UNLIMITED,
  networks: [...CARD_NETWORKS],
  source: 'FALLBACK',
})

describe('quota', () => {
  it('reads a real catalogue cap', () => {
    expect(quotaOf(catalog())).toEqual({ known: true, max: 3, issued: 1, remaining: 2, exhausted: false })
  })

  it('is exhausted once nothing remains', () => {
    expect(quotaOf(catalog({ issued: 3, remaining: 0 })).exhausted).toBe(true)
  })

  it('treats the -1 sentinel as UNKNOWN, never as "nothing left"', () => {
    const q = quotaOf(fallback())
    expect(q.known).toBe(false)
    expect(q.exhausted).toBe(false)
  })

  it('does not claim a cap when there is no entitlement document at all', () => {
    expect(quotaOf(null).known).toBe(false)
    expect(quotaOf(null).exhausted).toBe(false)
  })
})

describe('issue blockers', () => {
  const req = { cardType: 'DEBIT', network: 'VISA' } as const

  it('lets a well-formed request through', () => {
    expect(issueBlockers(catalog(), req)).toEqual([])
  })

  it('mirrors CARD_PRODUCT_DISABLED', () => {
    expect(issueBlockers(catalog({ enabled: false }), req)).toContain('CARD_PRODUCT_DISABLED')
  })

  it('mirrors CARD_VIRTUAL_NOT_ALLOWED for both virtual form factors', () => {
    const e = catalog({ virtualCardAllowed: false, singleUseAllowed: false })
    expect(issueBlockers(e, { cardType: 'VIRTUAL', network: 'VISA' })).toContain('CARD_VIRTUAL_NOT_ALLOWED')
    expect(issueBlockers(e, { cardType: 'SINGLE_USE', network: 'VISA' })).toContain('CARD_VIRTUAL_NOT_ALLOWED')
    // …and never gates a physical card on the virtual switch.
    expect(issueBlockers(e, { cardType: 'DEBIT', network: 'VISA' })).toEqual([])
  })

  it('mirrors CARD_NETWORK_NOT_ALLOWED', () => {
    expect(issueBlockers(catalog(), { cardType: 'DEBIT', network: 'AMEX' })).toContain('CARD_NETWORK_NOT_ALLOWED')
  })

  it('mirrors CARD_QUOTA_EXCEEDED', () => {
    expect(issueBlockers(catalog({ issued: 3, remaining: 0 }), req)).toContain('CARD_QUOTA_EXCEEDED')
  })

  it('blocks nothing on a FALLBACK document — the service skips its own gate then', () => {
    expect(issueBlockers(fallback(), { cardType: 'SINGLE_USE', network: 'UNIONPAY' })).toEqual([])
  })

  it('blocks nothing when the entitlement could not be read at all', () => {
    expect(issueBlockers(null, { cardType: 'VIRTUAL', network: 'AMEX' })).toEqual([])
  })
})

describe('offered choices', () => {
  it('hides the virtual form factors a product forbids', () => {
    expect(allowedCardTypes(catalog({ virtualCardAllowed: false, singleUseAllowed: false })))
      .toEqual(['DEBIT', 'CREDIT', 'PREPAID'])
  })

  it('offers everything when there is no entitlement document', () => {
    expect(allowedCardTypes(null)).toHaveLength(5)
  })

  it('narrows the networks to the product’s list', () => {
    expect(allowedNetworks(catalog(), CARD_NETWORKS)).toEqual(['VISA', 'MASTERCARD'])
  })

  it('offers every network when the product names none', () => {
    expect(allowedNetworks(catalog({ networks: [] }), CARD_NETWORKS)).toEqual([...CARD_NETWORKS])
  })
})
