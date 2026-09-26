// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { parseLedgerJournalPage } from '@/lib/ledger/ledgerJournalContract'

const line = {
  id: 'line-1', glAccountId: 'gl-1', side: 'DEBIT', amount: 1250, currencyCode: 'EUR',
  baseAmount: 1250, baseCurrencyCode: 'EUR', sequence: 1, subAccountId: 'account-1',
}
const entry = {
  id: 'journal-1', entryNumber: 42, transactionId: 'transaction-1', entryDate: '2026-09-10', valueDate: '2026-09-10',
  description: 'Customer transfer', status: 'POSTED', lines: [line], createdAt: '2026-09-10T06:00:00Z', synthetic: false,
}

describe('General Ledger evidence contract', () => {
  it('preserves validated accounting and provenance evidence', () => {
    expect(parseLedgerJournalPage({ data: [entry], pagination: { limit: 20, hasNextPage: true, nextCursor: 'next' } }, 20))
      .toMatchObject({ data: [{ synthetic: false, lines: [{ subAccountId: 'account-1' }] }], pagination: { nextCursor: 'next' } })
  })

  it('rejects malformed states, amounts, currencies, dates, and provenance', () => {
    for (const changed of [
      { status: 'DRAFT' }, { synthetic: undefined }, { entryDate: '2026-02-30' },
      { lines: [{ ...line, amount: Number.NaN }] }, { lines: [{ ...line, currencyCode: 'EURO' }] },
    ]) expect(() => parseLedgerJournalPage({ data: [{ ...entry, ...changed }], pagination: { limit: 20, hasNextPage: false } }, 20)).toThrow()
  })

  it('rejects an unexpected window, unusable cursor, and duplicate evidence', () => {
    expect(() => parseLedgerJournalPage({ data: [entry], pagination: { limit: 50, hasNextPage: false } }, 20)).toThrow('limit')
    expect(() => parseLedgerJournalPage({ data: [entry], pagination: { limit: 20, hasNextPage: true } }, 20)).toThrow('nextCursor')
    expect(() => parseLedgerJournalPage({ data: [entry, entry], pagination: { limit: 20, hasNextPage: false } }, 20)).toThrow('Duplicate ledger entry')
    expect(() => parseLedgerJournalPage({ data: [{ ...entry, lines: [line, line] }], pagination: { limit: 20, hasNextPage: false } }, 20)).toThrow('Duplicate ledger line')
  })
})
