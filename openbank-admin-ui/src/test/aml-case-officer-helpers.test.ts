// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { allowedTransitions, caseAgeHours, isOpenAmlStatus, parseAmlCase, partyDisplayName, requiresReason } from '@/lib/aml/amlCaseContract'
import { parsePartySummary } from '@/lib/aml/usePartyNames'

const base = {
  id: '11111111-1111-4111-8111-111111111111', partyId: '22222222-2222-4222-8222-222222222222',
  accountId: null, transactionId: null, customerReference: 'onboarding-22222222', screeningType: 'CUSTOMER_ONBOARDING',
  riskLevel: 'LOW', status: 'OPEN', alertCode: 'ONBOARDING_SCREENING', alertDetail: null, matchedEntity: null,
  decisionReason: null, assignedAnalyst: null, decidedBy: null, screenedAt: '2026-11-01T00:00:00Z',
  decidedAt: null, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z',
}

describe('AML officer helpers', () => {
  it('parses a single case with the same fail-fast contract as the list', () => {
    expect(parseAmlCase(base).id).toBe(base.id)
    expect(() => parseAmlCase({ ...base, status: 'DONE' })).toThrow('status')
  })

  it('offers exactly the transitions aml-service AmlCase.canTransitionTo accepts', () => {
    expect(allowedTransitions('OPEN')).toEqual(['UNDER_REVIEW', 'CLEARED', 'BLOCKED', 'ESCALATED'])
    expect(allowedTransitions('UNDER_REVIEW')).toEqual(['CLEARED', 'BLOCKED', 'ESCALATED'])
    expect(allowedTransitions('ESCALATED')).toEqual(['UNDER_REVIEW', 'CLEARED', 'BLOCKED'])
    expect(allowedTransitions('CLEARED')).toEqual([])
    expect(isOpenAmlStatus('BLOCKED')).toBe(false)
  })

  it('requires a rationale for every decision but not for taking a case into review', () => {
    expect(requiresReason('UNDER_REVIEW')).toBe(false)
    for (const s of ['CLEARED', 'BLOCKED', 'ESCALATED'] as const) expect(requiresReason(s)).toBe(true)
  })

  it('names the party by legal name, then trading name, never by blank', () => {
    expect(partyDisplayName({ legalName: 'Jan Novák', tradingName: 'JN s.r.o.' })).toBe('Jan Novák')
    expect(partyDisplayName({ legalName: '  ', tradingName: 'JN s.r.o.' })).toBe('JN s.r.o.')
    expect(partyDisplayName({ legalName: null })).toBeNull()
    expect(parsePartySummary({ legalName: 'Jan Novák', kycStatus: 'VERIFIED', piiMasked: true })).toMatchObject({ name: 'Jan Novák', kycStatus: 'VERIFIED', masked: true })
    expect(parsePartySummary([])).toBeNull()
  })

  it('measures case age in whole hours and never negative', () => {
    expect(caseAgeHours('2026-11-01T00:00:00Z', new Date('2026-11-02T01:30:00Z'))).toBe(25)
    expect(caseAgeHours('2026-11-03T00:00:00Z', new Date('2026-11-02T00:00:00Z'))).toBe(0)
  })
})
