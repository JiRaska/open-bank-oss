// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { ONBOARDING_STAGES, parseFunnelCounts, parseOnboardingRecordPage } from '@/lib/onboarding/evidence'

const record = {
  partyId: '11111111-1111-4111-8111-111111111111', legalName: 'Ada Bank', email: 'ada@example.test',
  partyStatus: 'PENDING_KYC', kycCaseId: null, kycStatus: 'OPEN', scaEnrolled: false, deviceCount: 0,
  funnelStage: 'KYC_OPEN', blockedReason: null, createdAt: '2026-09-09T10:00:00Z', updatedAt: '2026-09-09T10:01:00Z',
}

describe('onboarding operational evidence', () => {
  it('accepts only a complete non-negative funnel', () => {
    const valid = Object.fromEntries(ONBOARDING_STAGES.map(stage => [stage, 0]))
    expect(parseFunnelCounts(valid)).toEqual(valid)
    expect(parseFunnelCounts({ ...valid, ACTIVE: -1 })).toBeNull()
    expect(parseFunnelCounts(Object.fromEntries(Object.entries(valid).filter(([stage]) => stage !== 'BLOCKED')))).toBeNull()
  })

  it('verifies the requested page and stage', () => {
    expect(parseOnboardingRecordPage({ items: [record], total: 1, page: 0, size: 20, stageFilter: 'KYC_OPEN' }, 0, 'KYC_OPEN')?.items).toHaveLength(1)
    expect(parseOnboardingRecordPage({ items: [record], total: 1, page: 1, size: 20, stageFilter: 'KYC_OPEN' }, 0, 'KYC_OPEN')).toBeNull()
    expect(parseOnboardingRecordPage({ items: [{ ...record, funnelStage: 'ACTIVE' }], total: 1, page: 0, size: 20, stageFilter: 'KYC_OPEN' }, 0, 'KYC_OPEN')).toBeNull()
  })

  it('rejects malformed records instead of rendering partial customer evidence', () => {
    expect(parseOnboardingRecordPage({ items: [{ ...record, partyId: 'not-a-uuid' }], total: 1, page: 0, size: 20 }, 0, '')).toBeNull()
    expect(parseOnboardingRecordPage({ items: [record], total: 0, page: 0, size: 20 }, 0, '')).toBeNull()
  })

  it('accepts UUID syntax without inventing version-bit constraints absent from the API', () => {
    const versionless = { ...record, partyId: '00000000-1111-0000-0000-000000000001' }
    expect(parseOnboardingRecordPage({ items: [versionless], total: 1, page: 0, size: 20 }, 0, '')?.items[0].partyId)
      .toBe(versionless.partyId)
  })
})
