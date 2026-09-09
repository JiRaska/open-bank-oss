// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseFunnelCounts, parseOnboardingPage } from '@/lib/onboarding/evidenceContract'

const funnel = { REGISTERED: 12, KYC_OPEN: 4, KYC_UNDER_REVIEW: 1, SCA_PENDING: 3, ACTIVE: 40, BLOCKED: 1 }
const item = {
  partyId: 'party-1', legalName: 'Ada Banking', email: 'ada@example.test', partyStatus: 'ACTIVE',
  kycCaseId: null, kycStatus: null, scaEnrolled: true, deviceCount: 1, funnelStage: 'ACTIVE',
  blockedReason: null, createdAt: '2026-09-01T08:00:00Z', updatedAt: '2026-09-09T08:00:00Z',
}

describe('onboarding evidence contract', () => {
  it('accepts complete funnel and page evidence', () => {
    expect(parseFunnelCounts(funnel)).toEqual(funnel)
    expect(parseOnboardingPage({ items: [item], total: 1, page: 0, size: 20 }, 0).items).toEqual([item])
  })

  it.each([
    { ...funnel, ACTIVE: -1 },
    { ...funnel, BLOCKED: 1.5 },
    { ...funnel, KYC_OPEN: Number.NaN },
    { REGISTERED: 1 },
  ])('rejects malformed funnel evidence', raw => {
    expect(() => parseFunnelCounts(raw)).toThrow()
  })

  it.each([
    { items: [item], total: 1, page: 1, size: 20 },
    { items: [{ ...item, funnelStage: 'UNKNOWN' }], total: 1, page: 0, size: 20 },
    { items: [{ ...item, createdAt: 'never' }], total: 1, page: 0, size: 20 },
    { items: [{ ...item, deviceCount: -1 }], total: 1, page: 0, size: 20 },
    { items: [item], total: -1, page: 0, size: 20 },
  ])('rejects malformed or mismatched page evidence', raw => {
    expect(() => parseOnboardingPage(raw, 0)).toThrow()
  })
})
