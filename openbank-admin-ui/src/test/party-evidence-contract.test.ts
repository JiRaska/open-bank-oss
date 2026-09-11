// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { parsePartyEvidence } from '@/lib/parties/partyEvidenceContract'

const party = {
  id: 'party-1', partyType: 'INDIVIDUAL', status: 'ACTIVE', legalName: 'Test Customer',
  email: 'customer@example.test', kycStatus: 'APPROVED',
  address: { line1: 'Test 1', city: 'Prague', postalCode: '10000', countryCode: 'CZ' },
  createdAt: '2026-09-10T05:00:00Z', updatedAt: '2026-09-10T06:00:00Z',
}

describe('party detail evidence contract', () => {
  it('accepts complete evidence for the requested party', () => {
    expect(parsePartyEvidence(party, 'party-1')).toMatchObject({ id: 'party-1', legalName: 'Test Customer' })
  })

  it('rejects a successful response for another route identity', () => {
    expect(parsePartyEvidence(party, 'party-2')).toBeNull()
  })

  it('rejects malformed lifecycle, PII, and timestamp evidence', () => {
    expect(parsePartyEvidence({ ...party, status: 'UNKNOWN' }, 'party-1')).toBeNull()
    expect(parsePartyEvidence({ ...party, email: '' }, 'party-1')).toBeNull()
    expect(parsePartyEvidence({ ...party, updatedAt: 'later' }, 'party-1')).toBeNull()
    expect(parsePartyEvidence({ ...party, address: { city: 'Prague' } }, 'party-1')).toBeNull()
  })
})
