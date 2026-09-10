// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseLoyaltyCatalogue, parseLoyaltyParty } from '@/lib/loyalty/evidenceContract'

const PARTY = '11111111-1111-4111-8111-111111111111'
const catalogue = {
  state: 'ok',
  benefits: [{ id: 'fee-waiver', engine: 'pricing', priceLeaves: 100, validityDays: 30, description: 'Fee waiver' }],
  earnSources: [{ id: 'savings-goal', leaves: 20, validityDays: 730 }],
  provisioning: { at: '2026-09-09T08:00:00Z', outstandingLeaves: 1200, annualCapPerParty: 1000, ruleVersion: 'v1' },
}
const party = {
  state: 'ok',
  partyId: PARTY,
  balance: 80,
  earnedThisYear: 120,
  earnedTotal: 300,
  nextExpiry: '2026-12-01T00:00:00Z',
  history: [{
    id: 'entry-1', type: 'EARN', leaves: 100, remainingLeaves: 80, earnSourceId: 'savings-goal',
    benefitId: null, ruleVersion: 'v1', occurredAt: '2026-08-01T10:00:00Z', expiresAt: '2028-08-01T10:00:00Z',
  }],
}

describe('Lípa evidence contract', () => {
  it('accepts complete catalogue and customer evidence', () => {
    expect(parseLoyaltyCatalogue(catalogue)).toEqual(catalogue)
    expect(parseLoyaltyParty(party, PARTY)).toEqual(party)
  })

  it.each([
    { ...catalogue, benefits: [{ ...catalogue.benefits[0], priceLeaves: -1 }] },
    { ...catalogue, provisioning: { ...catalogue.provisioning, outstandingLeaves: Number.NaN } },
    { ...catalogue, provisioning: { ...catalogue.provisioning, at: 'not-a-date' } },
    { ...catalogue, earnSources: null },
  ])('rejects malformed catalogue evidence', raw => {
    expect(() => parseLoyaltyCatalogue(raw)).toThrow()
  })

  it.each([
    { ...party, partyId: '22222222-2222-4222-8222-222222222222' },
    { ...party, balance: -1 },
    { ...party, earnedTotal: 1.5 },
    { ...party, history: [{ ...party.history[0], occurredAt: 'never' }] },
    { ...party, history: [{ ...party.history[0], type: 'CREDIT' }] },
  ])('rejects malformed or mismatched customer evidence', raw => {
    expect(() => parseLoyaltyParty(raw, PARTY)).toThrow()
  })

  it('keeps an unavailable service distinct from a real zero balance', () => {
    expect(parseLoyaltyParty({ state: 'unreachable' }, PARTY)).toMatchObject({ state: 'unreachable', balance: 0 })
    expect(parseLoyaltyParty({ ...party, balance: 0 }, PARTY)).toMatchObject({ state: 'ok', balance: 0 })
  })
})
