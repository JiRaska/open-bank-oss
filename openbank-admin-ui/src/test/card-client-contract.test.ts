// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseAccountRef, parseCard, parseCardEntitlements, parseCardList, parsePartyRef } from '@/lib/cards/clientContract'

const validCard = {
  id: '11111111-1111-4111-8111-111111111111',
  partyId: '22222222-2222-4222-8222-222222222222',
  accountId: '33333333-3333-4333-8333-333333333333',
  productCode: 'DEBIT-CLASSIC', cardType: 'DEBIT', network: 'VISA',
  maskedPan: '411111******4242', cardholderName: 'Alice Example', embossedName: 'ALICE EXAMPLE',
  expiryDate: '2029-12-31', status: 'ACTIVE', dailyLimitMinorUnits: 500_000,
  monthlyLimitMinorUnits: 2_000_000, currency: 'CZK', createdAt: '2026-09-10T08:00:00Z',
}

describe('card detail client contract', () => {
  it('accepts the service DTO and retains only the PCI-safe allow-list', () => {
    const parsed = parseCard({ ...validCard, pan: '4111111111114242', cvv: '123', serverOnly: true })
    expect(parsed).toEqual(validCard)
    expect(parsed).not.toHaveProperty('pan')
    expect(parsed).not.toHaveProperty('cvv')
    expect(parsed).not.toHaveProperty('serverOnly')
  })

  it.each([
    ['unmasked PAN', { maskedPan: '4111111111114242' }],
    ['unknown lifecycle', { status: 'UNKNOWN' }],
    ['unsafe integer', { dailyLimitMinorUnits: Number.MAX_SAFE_INTEGER + 1 }],
    ['daily limit over monthly limit', { dailyLimitMinorUnits: 3_000_000 }],
    ['invalid timestamp', { createdAt: 'today' }],
    ['invalid identifier', { partyId: 'party-1' }],
  ])('rejects %s', (_label, change) => {
    expect(() => parseCard({ ...validCard, ...change })).toThrow()
  })

  it('rejects a mixed-validity sibling list instead of partially trusting it', () => {
    expect(() => parseCardList([validCard, { ...validCard, currency: 'czk' }])).toThrow()
  })

  it('accepts the service terminal CONSUMED state', () => {
    expect(parseCard({ ...validCard, cardType: 'SINGLE_USE', status: 'CONSUMED' }).status).toBe('CONSUMED')
  })

  it('parses data-minimised party and account references', () => {
    expect(parsePartyRef({ id: validCard.partyId, legalName: 'Alice Example', phone: '+420-secret' }))
      .toEqual({ id: validCard.partyId, legalName: 'Alice Example', tradingName: undefined, email: undefined, status: undefined, kycStatus: undefined, partyType: undefined })
    expect(parseAccountRef({
      id: validCard.accountId, accountNumber: 'CZ001', accountType: 'CURRENT', partyId: validCard.partyId,
      productId: '44444444-4444-4444-8444-444444444444', currencyCode: 'CZK', status: 'ACTIVE', balance: 99,
    })).not.toHaveProperty('balance')
  })

  it('preserves the service fallback quota sentinel but rejects malformed entitlements', () => {
    const fallback = {
      productCode: 'DEBIT-CLASSIC', maxCards: -1, issued: 2, remaining: -1,
      virtualCardAllowed: true, singleUseAllowed: false, networks: ['VISA'], tiers: ['STANDARD'],
      monthlyFeePerCard: 0, enabled: true, source: 'FALLBACK',
    }
    expect(parseCardEntitlements(fallback).remaining).toBe(-1)
    expect(() => parseCardEntitlements({ ...fallback, networks: ['DINERS'] })).toThrow()
  })
})
