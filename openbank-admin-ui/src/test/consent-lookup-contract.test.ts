// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseConsentList } from '@/lib/consents/consentLookup'

const validConsent = {
  id: 'consent-1',
  partyId: 'party-1',
  granteeId: 'grantee-1',
  granteeType: 'INTERNAL_SERVICE',
  granteeName: 'Marketing communications',
  scopes: ['MARKETING_COMMS_EMAIL'],
  accountIbans: null,
  status: 'ACTIVE',
  validFrom: '2026-06-01T00:00:00Z',
  validTo: '2026-12-01T00:00:00Z',
  createdAt: '2026-05-31T10:00:00Z',
}

describe('consent lookup contract', () => {
  it('accepts the complete service contract', () => {
    expect(parseConsentList([validConsent])).toEqual([validConsent])
  })

  it.each([
    ['non-array envelope', { items: [validConsent] }],
    ['invalid validity date', [{ ...validConsent, validTo: 'not-a-date' }]],
    ['reversed validity window', [{ ...validConsent, validFrom: '2027-01-01T00:00:00Z' }]],
    ['non-string scope', [{ ...validConsent, scopes: ['PAYMENTS', 7] }]],
    ['non-string account IBAN', [{ ...validConsent, accountIbans: ['CZ1200', null] }]],
  ])('rejects %s before it reaches the table', (_label, payload) => {
    expect(() => parseConsentList(payload)).toThrow('invalid consent lookup payload')
  })
})
