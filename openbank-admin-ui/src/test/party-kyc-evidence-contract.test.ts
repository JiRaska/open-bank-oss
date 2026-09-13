// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { parseKycCaseEvidence, parseKycCasePageEvidence } from '@/lib/parties/kycEvidenceContract'

const evidence = {
  id: 'case-1', partyId: 'party-1', status: 'UNDER_REVIEW', riskLevel: 'HIGH',
  checks: [{ id: 'check-1', caseId: 'case-1', checkType: 'IDENTITY', status: 'PASSED', result: 'verified' }],
  reviewedBy: 'operator-2', createdAt: '2026-09-10T05:00:00Z', updatedAt: '2026-09-10T06:00:00Z',
}

describe('party KYC evidence contract', () => {
  it('accepts the runtime list-of-checks shape', () => {
    expect(parseKycCaseEvidence(evidence, 'party-1')).toMatchObject({
      id: 'case-1', partyId: 'party-1', checks: [{ id: 'check-1', checkType: 'IDENTITY', status: 'PASSED' }],
    })
  })

  it('rejects evidence belonging to another party', () => {
    expect(parseKycCaseEvidence(evidence, 'party-2')).toBeNull()
  })

  it('rejects the stale map-shaped checks contract and malformed states', () => {
    expect(parseKycCaseEvidence({ ...evidence, checks: { IDENTITY: 'PASSED' } }, 'party-1')).toBeNull()
    expect(parseKycCaseEvidence({ ...evidence, riskLevel: 'UNBOUNDED' }, 'party-1')).toBeNull()
    expect(parseKycCaseEvidence({ ...evidence, checks: [{ ...evidence.checks[0], status: 'UNKNOWN' }] }, 'party-1')).toBeNull()
  })

  it('validates every case and the exact requested collection window', () => {
    const page = { items: [evidence], total: 1, page: 0, size: 20, statusFilter: null }
    expect(parseKycCasePageEvidence(page, 0, 20)?.items).toHaveLength(1)
    expect(parseKycCasePageEvidence({ ...page, page: 1 }, 0, 20)).toBeNull()
    expect(parseKycCasePageEvidence({ ...page, items: [{ ...evidence, checks: {} }] }, 0, 20)).toBeNull()
  })
})
