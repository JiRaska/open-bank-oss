// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseConsentEvidenceList } from '@/lib/consents/consentContract'

const consent = {
  id: '11111111-1111-4111-8111-111111111111',
  partyId: '22222222-2222-4222-8222-222222222222',
  granteeId: 'party-service:marketing-comms',
  granteeType: 'INTERNAL_SERVICE',
  granteeName: 'Marketing service',
  scopes: ['MARKETING_COMMS_EMAIL'],
  accountIbans: null,
  status: 'ACTIVE',
  validFrom: '2026-01-01T00:00:00Z',
  validTo: '2026-12-31T00:00:00Z',
  createdAt: '2026-01-01T00:00:00Z',
}

describe('consent response contract', () => {
  it('preserves the authority and account-coverage evidence', () => {
    expect(parseConsentEvidenceList([{ ...consent, accountIbans: ['CZ6508000000192000145399'] }]))
      .toMatchObject([{ status: 'ACTIVE', scopes: ['MARKETING_COMMS_EMAIL'], accountIbans: ['CZ6508000000192000145399'] }])
  })

  it.each([
    [{ ...consent, status: 'APPROVED' }, 'status'],
    [{ ...consent, scopes: ['ALL_ACCESS'] }, 'scopes'],
    [{ ...consent, validTo: 'not-a-date' }, 'validTo'],
    [{ ...consent, validTo: consent.validFrom }, 'validity'],
  ])('rejects malformed consent evidence', (candidate, message) => {
    expect(() => parseConsentEvidenceList([candidate])).toThrow(message)
  })
})
