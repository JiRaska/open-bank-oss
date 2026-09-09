// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseLedgerJournalPage } from './ledgerJournalContract'

const entry = {
  id: 'journal-1', entryNumber: 42, transactionId: 'transaction-1', entryDate: '2026-09-09', valueDate: '2026-09-09',
  description: 'Customer transfer', status: 'POSTED', createdAt: '2026-09-09T12:00:00Z', synthetic: false,
  lines: [{
    id: 'line-1', glAccountId: 'gl-1', side: 'DEBIT', amount: 1250, currencyCode: 'EUR', baseAmount: 1250,
    baseCurrencyCode: 'EUR', sequence: 1, subAccountId: 'sub-account-1',
  }],
}

describe('ledger journal response contract', () => {
  it('preserves provenance and cursor evidence', () => {
    expect(parseLedgerJournalPage({ data: [entry], pagination: { limit: 20, hasNextPage: true, nextCursor: 'next', totalCount: 21 } }))
      .toMatchObject({ data: [{ synthetic: false, lines: [{ subAccountId: 'sub-account-1' }] }], pagination: { nextCursor: 'next', totalCount: 21 } })
  })

  it('rejects invented states, missing provenance and unusable cursors', () => {
    expect(() => parseLedgerJournalPage({ data: [{ ...entry, status: 'DRAFT' }], pagination: { limit: 20, hasNextPage: false } })).toThrow('Invalid ledger status')
    expect(() => parseLedgerJournalPage({ data: [{ ...entry, synthetic: undefined }], pagination: { limit: 20, hasNextPage: false } })).toThrow('Invalid ledger synthetic')
    expect(() => parseLedgerJournalPage({ data: [entry], pagination: { limit: 20, hasNextPage: true } })).toThrow('Invalid ledger nextCursor')
  })
})
