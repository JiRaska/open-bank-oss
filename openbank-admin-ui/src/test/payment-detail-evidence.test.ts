// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { parsePaymentDetailEvidence } from '@/lib/payments/detailEvidence'

const ID = '123e4567-e89b-42d3-a456-426614174000'
const base = {
  id: ID,
  status: 'RECEIVED',
  amount: 12.5,
  currency: 'EUR',
  createdAt: '2026-09-09T12:00:00Z',
}

describe('payment detail evidence boundary', () => {
  it('normalizes a domestic string amount and keeps only declared fields', () => {
    expect(parsePaymentDetailEvidence({
      ...base,
      status: 'SETTLED',
      amount: '125.50',
      currencyCode: 'CZK',
      currency: undefined,
      creditorAccountNumber: '1234567890',
      secretInternalNote: 'must not be disclosed',
    }, ID, 'DOMESTIC')).toEqual({
      id: ID,
      type: 'DOMESTIC',
      status: 'SETTLED',
      amount: 125.5,
      currency: 'CZK',
      creditorAccountNumber: '1234567890',
      createdAt: base.createdAt,
    })
  })

  it('rejects mismatched identity and malformed core evidence', () => {
    expect(parsePaymentDetailEvidence({ ...base, id: '223e4567-e89b-42d3-a456-426614174000' }, ID, 'SEPA')).toBeNull()
    expect(parsePaymentDetailEvidence({ ...base, amount: 'NaN' }, ID, 'SEPA')).toBeNull()
    expect(parsePaymentDetailEvidence({ ...base, currency: 'eur' }, ID, 'SEPA')).toBeNull()
    expect(parsePaymentDetailEvidence({ ...base, createdAt: 'not-a-date' }, ID, 'SEPA')).toBeNull()
    expect(parsePaymentDetailEvidence({ ...base, status: 'SETTLED' }, ID, 'SEPA')).toBeNull()
  })

  it('rejects malformed optional fields instead of partially blessing the record', () => {
    expect(parsePaymentDetailEvidence({ ...base, creditorName: 42 }, ID, 'SEPA')).toBeNull()
    expect(parsePaymentDetailEvidence({ ...base, updatedAt: 'yesterday' }, ID, 'SEPA')).toBeNull()
  })
})
