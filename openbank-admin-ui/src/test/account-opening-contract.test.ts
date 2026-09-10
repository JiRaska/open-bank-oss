// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseAccountOpeningProducts, parseOpenedAccount } from '@/lib/accounts/openingContract'

const expected = {
  partyId: '00000000-1111-0000-0000-000000000001',
  productId: '00000000-2222-0000-0000-000000000002',
  accountType: 'TERM_DEPOSIT',
  currencyCode: 'CZK',
}

const response = {
  id: '00000000-3333-0000-0000-000000000003',
  accountNumber: 'TEST-ACCOUNT-0003',
  ...expected,
  status: 'ACTIVE',
  openedAt: '2026-09-10T08:00:00Z',
}

describe('account opening response contracts', () => {
  it('retains only valid active customer-account products', () => {
    const product = { id: expected.productId, code: 'TERM_DEPOSIT_6M_CZK', name: 'Term deposit', type: 'TERM_DEPOSIT', currency: 'CZK', status: 'ACTIVE', internal: true,
      termsAndConditions: [{ version: '2.1', url: 'https://example.test/terms/2.1', effectiveFrom: '2026-01-01', effectiveTo: null }],
    }
    expect(parseAccountOpeningProducts([product], new Date('2026-09-10T00:00:00Z'))).toEqual([{
      id: expected.productId, code: 'TERM_DEPOSIT_6M_CZK', name: 'Term deposit',
      type: 'TERM_DEPOSIT', currency: 'CZK', status: 'ACTIVE',
      terms: { version: '2.1', url: 'https://example.test/terms/2.1', effectiveFrom: '2026-01-01' },
    }])
  })

  it.each([
    ['wrapper instead of the service array', { products: [] }],
    ['inactive item', [{ id: expected.productId, code: 'X', name: 'X', type: 'CURRENT', currency: 'CZK', status: 'DRAFT', termsAndConditions: [] }]],
    ['non-customer product', [{ id: expected.productId, code: 'LOAN', name: 'Loan', type: 'LOAN', currency: 'CZK', status: 'ACTIVE', termsAndConditions: [] }]],
    ['invalid currency', [{ id: expected.productId, code: 'X', name: 'X', type: 'CURRENT', currency: 'czk', status: 'ACTIVE', termsAndConditions: [] }]],
    ['term deposit without current terms', [{ id: expected.productId, code: 'TD', name: 'Deposit', type: 'TERM_DEPOSIT', currency: 'CZK', status: 'ACTIVE', termsAndConditions: [] }]],
  ])('rejects %s', (_label, raw) => {
    expect(() => parseAccountOpeningProducts(raw)).toThrow()
  })

  it('accepts a complete matching AccountResponse and allow-lists it', () => {
    expect(parseOpenedAccount({ ...response, balance: 1_000_000 }, expected)).toEqual(response)
  })

  it.each([
    ['partial success', { id: response.id }],
    ['different party', { ...response, partyId: '00000000-9999-0000-0000-000000000009' }],
    ['different product', { ...response, productId: '00000000-9999-0000-0000-000000000009' }],
    ['different type', { ...response, accountType: 'CURRENT' }],
    ['different currency', { ...response, currencyCode: 'EUR' }],
    ['invalid status', { ...response, status: 'CREATED' }],
  ])('rejects %s instead of redirecting to an unproven account', (_label, raw) => {
    expect(() => parseOpenedAccount(raw, expected)).toThrow()
  })
})
