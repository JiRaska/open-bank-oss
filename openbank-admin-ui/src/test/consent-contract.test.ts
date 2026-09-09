// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { parseConsentEvidenceList } from '@/lib/consents/consentContract'

const pageSource = readFileSync(path.resolve(__dirname, '../app/consents/page.tsx'), 'utf8')

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
      .toMatchObject({ value: [{ status: 'ACTIVE', scopes: ['MARKETING_COMMS_EMAIL'], accountIbans: ['CZ6508000000192000145399'] }], excludedCount: 0 })
  })

  it.each([
    { ...consent, status: 'APPROVED' },
    { ...consent, scopes: ['ALL_ACCESS'] },
    { ...consent, validTo: 'not-a-date' },
    { ...consent, validTo: consent.validFrom },
  ])('excludes malformed consent evidence', candidate => {
    expect(parseConsentEvidenceList([consent, candidate])).toEqual({ value: [consent], excludedCount: 1 })
  })

  it('distinguishes an invalid envelope from a legitimate empty lookup', () => {
    expect(parseConsentEvidenceList({ consents: [] })).toEqual({ value: null, excludedCount: 0 })
    expect(parseConsentEvidenceList([])).toEqual({ value: [], excludedCount: 0 })
  })

  it('discloses partial exclusions and rejects an all-invalid successful response', () => {
    expect(pageSource).toContain('parsed.value.length === 0 && parsed.excludedCount > 0')
    expect(pageSource).toContain('excludedCount > 0')
    expect(pageSource).toContain('Displayed totals use validated records only.')
    expect(pageSource).toContain('role="alert"')
  })
})
