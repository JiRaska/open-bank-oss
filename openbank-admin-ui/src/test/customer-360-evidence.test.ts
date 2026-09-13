// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { parseCustomer360Evidence } from '@/lib/customer360/evidence'

const PARTY = '11111111-1111-4111-8111-111111111111'
const valid = {
  available: true,
  partyId: PARTY,
  asOf: '2026-09-09 12:00:00.000',
  domains: [{ aggregateType: 'party', events: 2, lastEventType: 'PartyUpdated', lastOccurredAt: '2026-09-09 12:00:00.000' }],
  accountIds: ['account-1'],
  consents: [{ consentId: 'consent-1', status: 'ACTIVE', scopes: ['ACCOUNTS_READ'] }],
  excludedCount: 1,
}

describe('Customer 360 browser evidence boundary', () => {
  it('accepts a valid same-party projection', () => {
    expect(parseCustomer360Evidence(valid, PARTY)).toEqual(valid)
  })

  it('rejects evidence for another party or malformed summaries', () => {
    expect(parseCustomer360Evidence({ ...valid, partyId: '22222222-2222-4222-8222-222222222222' }, PARTY)).toBeNull()
    expect(parseCustomer360Evidence({ ...valid, asOf: 'not-a-date' }, PARTY)).toBeNull()
    expect(parseCustomer360Evidence({ ...valid, domains: [{ ...valid.domains[0], events: Number.NaN }] }, PARTY)).toBeNull()
    expect(parseCustomer360Evidence({ ...valid, consents: [{ ...valid.consents[0], scopes: [42] }] }, PARTY)).toBeNull()
    expect(parseCustomer360Evidence({ ...valid, excludedCount: -1 }, PARTY)).toBeNull()
  })
})
