// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The issue flow's whole job is that an operator never types a UUID: partyId,
// accountId, productCode and currency are all WALKED to. These tests assert the
// walk cannot be short-circuited — no request body exists until every identifier
// has been derived from a real selection.

import { describe, it, expect } from 'vitest'
import {
  DEFAULT_DAILY_LIMIT_MINOR, DEFAULT_MONTHLY_LIMIT_MINOR,
  canAdvance, initialDraft, issueRequestBody, nextStep, prevStep, reachable,
  stepBlockers, toEmbossedName, withAccountSelected, withPartySelected,
  type IssueDraft,
} from '@/lib/cards/issue'
import type { AccountRef, CardEntitlements, PartyRef } from '@/lib/cards/types'

const party: PartyRef = { id: 'p-1', legalName: 'Bohuslava Čermáková', email: 'b@example.test', status: 'ACTIVE' }
const account: AccountRef = {
  id: 'a-1', accountNumber: 'CZ6508000000192000145399', accountType: 'CURRENT',
  partyId: 'p-1', productId: 'prod-1', currencyCode: 'CZK', status: 'ACTIVE',
}
const entitlements: CardEntitlements = {
  productCode: 'CURRENT_CZK', maxCards: 3, issued: 1, remaining: 2,
  virtualCardAllowed: true, singleUseAllowed: true, networks: ['VISA'],
  tiers: [], monthlyFeePerCard: 0, enabled: true, source: 'CATALOG',
}

function complete(): IssueDraft {
  let d = withPartySelected(initialDraft(), party)
  d = withAccountSelected(d, account)
  return { ...d, productCode: 'CURRENT_CZK', entitlements }
}

describe('embossing line', () => {
  it('folds diacritics, upper-cases and stays within 26 characters', () => {
    expect(toEmbossedName('Bohuslava Čermáková')).toBe('BOHUSLAVA CERMAKOVA')
    expect(toEmbossedName('Jiří Řehoř')).toBe('JIRI REHOR')
    expect(toEmbossedName('Bohuslava Čermáková-Nováková')).toHaveLength(26)
  })

  it('drops characters an embosser cannot print', () => {
    expect(toEmbossedName('Anna (Ann) Nováková, 2nd')).toBe('ANNA ANN NOVAKOVA ND')
  })
})

describe('prefill', () => {
  it('takes the cardholder and embossed names from the party — nothing is retyped', () => {
    const d = withPartySelected(initialDraft(), party)
    expect(d.cardholderName).toBe('Bohuslava Čermáková')
    expect(d.embossedName).toBe('BOHUSLAVA CERMAKOVA')
  })

  it('starts from the service’s own default limits', () => {
    expect(initialDraft().dailyMinorUnits).toBe(DEFAULT_DAILY_LIMIT_MINOR)
    expect(initialDraft().monthlyMinorUnits).toBe(DEFAULT_MONTHLY_LIMIT_MINOR)
  })

  it('invalidates the account, product and entitlement when the party changes', () => {
    const d = withPartySelected(complete(), { id: 'p-2', legalName: 'Other' })
    expect(d.account).toBeNull()
    expect(d.productCode).toBeNull()
    expect(d.entitlements).toBeNull()
  })

  it('invalidates the product and entitlement when the account changes', () => {
    const d = withAccountSelected(complete(), { ...account, id: 'a-2', productId: 'prod-2' })
    expect(d.productCode).toBeNull()
    expect(d.entitlements).toBeNull()
  })
})

describe('step gating', () => {
  it('will not leave the party step without a party', () => {
    expect(stepBlockers('party', initialDraft())).toEqual(['no_party'])
    expect(canAdvance('party', withPartySelected(initialDraft(), party))).toBe(true)
  })

  it('will not leave the account step without an ACTIVE account', () => {
    const noAccount = withPartySelected(initialDraft(), party)
    expect(stepBlockers('account', noAccount)).toEqual(['no_account'])
    const closed = withAccountSelected(noAccount, { ...account, status: 'CLOSED' })
    expect(stepBlockers('account', closed)).toEqual(['account_not_active'])
    expect(canAdvance('account', withAccountSelected(noAccount, account))).toBe(true)
  })

  it('will not configure a card whose product could not be resolved', () => {
    const d = { ...complete(), productCode: null }
    expect(stepBlockers('configure', d)).toContain('no_product')
  })

  it('surfaces an entitlement refusal as its own blocker, named after the service code', () => {
    const d = { ...complete(), cardType: 'VIRTUAL' as const, entitlements: { ...entitlements, virtualCardAllowed: false, singleUseAllowed: false } }
    expect(stepBlockers('configure', d)).toContain('entitlement:CARD_VIRTUAL_NOT_ALLOWED')
  })

  it('applies the same limit invariants the aggregate applies to a PENDING card', () => {
    const d = { ...complete(), dailyMinorUnits: 9_000_000 }
    expect(stepBlockers('review', d)).toContain('limits:daily_exceeds_monthly')
    expect(stepBlockers('review', { ...complete(), dailyMinorUnits: null })).toContain('limits:daily_not_a_number')
  })

  it('refuses a name the operator cleared', () => {
    expect(stepBlockers('review', { ...complete(), embossedName: '  ' })).toContain('no_embossed_name')
  })

  it('walks forward and back without falling off either end', () => {
    expect(nextStep('party')).toBe('account')
    expect(nextStep('review')).toBe('review')
    expect(prevStep('account')).toBe('party')
    expect(prevStep('party')).toBe('party')
  })

  it('keeps later steps unreachable until the earlier ones are satisfied', () => {
    expect(reachable('review', initialDraft())).toBe(false)
    expect(reachable('party', initialDraft())).toBe(true)
    expect(reachable('review', complete())).toBe(true)
  })
})

describe('request body', () => {
  it('derives every identifier from the selections — none is typed', () => {
    expect(issueRequestBody(complete())).toEqual({
      partyId: 'p-1',
      accountId: 'a-1',
      productCode: 'CURRENT_CZK',
      cardType: 'DEBIT',
      network: 'VISA',
      cardholderName: 'Bohuslava Čermáková',
      embossedName: 'BOHUSLAVA CERMAKOVA',
      currency: 'CZK',
      dailyLimitMinorUnits: DEFAULT_DAILY_LIMIT_MINOR,
      monthlyLimitMinorUnits: DEFAULT_MONTHLY_LIMIT_MINOR,
    })
  })

  it('is null for any incomplete or refused draft — never a partial POST', () => {
    expect(issueRequestBody(initialDraft())).toBeNull()
    expect(issueRequestBody({ ...complete(), productCode: null })).toBeNull()
    expect(issueRequestBody({ ...complete(), dailyMinorUnits: null })).toBeNull()
    expect(issueRequestBody({ ...complete(), entitlements: { ...entitlements, remaining: 0, issued: 3 } })).toBeNull()
    expect(issueRequestBody({ ...complete(), account: { ...account, status: 'CLOSED' } })).toBeNull()
  })
})
