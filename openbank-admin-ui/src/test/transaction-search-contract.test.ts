// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseTransactionSearchResult } from '@/lib/transactions/transactionSearchContract'

const row = {
  id: '11111111-1111-4111-8111-111111111111', referenceNumber: 'TXN-42', type: 'CREDIT',
  sourceAccountId: null, targetAccountId: '22222222-2222-4222-8222-222222222222', amount: 1250,
  currencyCode: 'CZK', status: 'COMPLETED', description: 'Settlement', valueDate: '2026-09-09',
  bookingDate: '2026-09-09', initiatedAt: '2026-09-09T08:00:00Z', completedAt: '2026-09-09T08:00:01Z',
}

describe('transaction search response contract', () => {
  it('preserves money-path and paging evidence', () => {
    expect(parseTransactionSearchResult({ data: [row], count: 1, limit: 50, offset: 0 }))
      .toMatchObject({ data: [{ amount: 1250, currencyCode: 'CZK', status: 'COMPLETED' }], count: 1, limit: 50, offset: 0 })
  })

  it.each([
    [{ ...row, status: 'SETTLED' }, 'status'],
    [{ ...row, type: 'PAYMENT' }, 'type'],
    [{ ...row, amount: Number.NaN }, 'amount'],
    [{ ...row, currencyCode: 'CZ' }, 'currencyCode'],
    [{ ...row, bookingDate: '2026-02-30' }, 'bookingDate'],
  ])('rejects malformed transaction evidence', (candidate, message) => {
    expect(() => parseTransactionSearchResult({ data: [candidate], count: 1, limit: 50, offset: 0 })).toThrow(message)
  })

  it('rejects paging metadata that does not describe the returned window', () => {
    expect(() => parseTransactionSearchResult({ data: [row], count: 0, limit: 50, offset: 0 })).toThrow('count')
  })

  it.each([
    [{ ...row, initiatedAt: '2026-09-09 08:00:00' }, 'initiatedAt'],
    [{ ...row, status: 'PENDING', completedAt: row.completedAt }, 'lifecycle'],
    [{ ...row, status: 'COMPLETED', completedAt: null }, 'lifecycle'],
    [{ ...row, completedAt: '2026-09-09T07:59:59Z' }, 'lifecycle'],
    [{ ...row, referenceNumber: 'x'.repeat(201) }, 'referenceNumber'],
  ])('rejects ambiguous transaction evidence', (candidate, message) => {
    expect(() => parseTransactionSearchResult({ data: [candidate], count: 1, limit: 50, offset: 0 })).toThrow(message)
  })

  it('rejects duplicate transaction identities', () => {
    expect(() => parseTransactionSearchResult({ data: [row, row], count: 2, limit: 50, offset: 0 })).toThrow('Duplicate')
  })
})
