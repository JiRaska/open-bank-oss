// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { parseFraudCaseNetwork } from '@/lib/context/fraudCaseNetwork'

const rootId = '11111111-1111-4111-8111-111111111111'
const relatedId = '22222222-2222-4222-8222-222222222222'
const account = '33333333-3333-4333-8333-333333333333'
const counterparty = '44444444-4444-4444-8444-444444444444'
const openedAt = '2026-09-17T12:00:00Z'
const root = { caseId: rootId, scoreId: '55555555-5555-4555-8555-555555555555', accountId: account, counterpartyId: counterparty, status: 'OPEN', revision: 1, openedAt, closedAt: null }
const related = { ...root, caseId: relatedId, scoreId: '66666666-6666-4666-8666-666666666666' }

describe('Fraud network boundary', () => {
  it('accepts only explicit same-role source references', () => {
    const network = parseFraudCaseNetwork({ root, related: [{ evidence: related, shared: [
      { type: 'ACCOUNT', sourceId: account }, { type: 'COUNTERPARTY', sourceId: counterparty },
    ] }], inspectedCandidates: 2, candidateTruncated: true })
    expect(network.related[0].shared.map(ref => ref.type)).toEqual(['ACCOUNT', 'COUNTERPARTY'])
    expect(network.candidateTruncated).toBe(true)
    expect(parseFraudCaseNetwork({ root, related: [], inspectedCandidates: 6, candidateTruncated: false }).inspectedCandidates).toBe(6)
  })

  it('rejects invented, cross-role, closed and over-limit connections', () => {
    const crossed = { ...related, accountId: counterparty, counterpartyId: account }
    expect(() => parseFraudCaseNetwork({ root, related: [{ evidence: crossed, shared: [{ type: 'ACCOUNT', sourceId: counterparty }] }], inspectedCandidates: 1, candidateTruncated: false })).toThrow()
    expect(() => parseFraudCaseNetwork({ root, related: [{ evidence: related, shared: [{ type: 'ACCOUNT', sourceId: counterparty }] }], inspectedCandidates: 1, candidateTruncated: false })).toThrow()
    expect(() => parseFraudCaseNetwork({ root: { ...root, status: 'CLOSED_NO_FINDING' }, related: [], inspectedCandidates: 0, candidateTruncated: false })).toThrow()
    expect(() => parseFraudCaseNetwork({ root, related: [], inspectedCandidates: 257, candidateTruncated: false })).toThrow()
  })
})
