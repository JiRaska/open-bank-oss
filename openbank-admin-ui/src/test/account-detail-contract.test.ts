// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { validateAccountBalance, validateAccountDetail } from '@/lib/accounts/detailContract'

const account = {
  id: 'account-7',
  accountNumber: 'TEST-ACCOUNT-0007',
  accountType: 'CURRENT',
  partyId: 'party-2',
  productId: 'product-1',
  currencyCode: 'CZK',
  status: 'ACTIVE',
  openedAt: '2026-09-10T08:30:00Z',
}

const balance = {
  accountId: account.id,
  availableBalance: 1250,
  currentBalance: 1300,
  reservedBalance: 50,
  pendingBalance: 0,
  currencyCode: account.currencyCode,
  lastUpdatedAt: '2026-09-10T08:31:00Z',
}

describe('account detail evidence contract', () => {
  it('accepts account and balance evidence bound to the requested account', () => {
    const verifiedAccount = validateAccountDetail(account, account.id)
    expect(verifiedAccount).toEqual(account)
    expect(validateAccountBalance(balance, verifiedAccount!)).toEqual(balance)
  })

  it('rejects an account returned for a different route identity', () => {
    expect(validateAccountDetail(account, 'account-8')).toBeNull()
  })

  it('rejects malformed account evidence', () => {
    expect(validateAccountDetail({ ...account, status: 'UNKNOWN' }, account.id)).toBeNull()
    expect(validateAccountDetail({ ...account, openedAt: 'not-a-date' }, account.id)).toBeNull()
    expect(validateAccountDetail({ ...account, accountNumber: '' }, account.id)).toBeNull()
  })

  it('rejects balance evidence for another account or currency', () => {
    expect(validateAccountBalance({ ...balance, accountId: 'account-8' }, account)).toBeNull()
    expect(validateAccountBalance({ ...balance, currencyCode: 'EUR' }, account)).toBeNull()
  })

  it('rejects non-finite amounts and invalid timestamps', () => {
    expect(validateAccountBalance({ ...balance, availableBalance: Number.NaN }, account)).toBeNull()
    expect(validateAccountBalance({ ...balance, lastUpdatedAt: 'later' }, account)).toBeNull()
  })
})
