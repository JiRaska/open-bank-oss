import { describe, expect, it } from 'vitest'
import { parseAmlCases } from '@/lib/aml/amlCaseContract'

const amlCase = {
  id: 'case-1', partyId: 'party-1', accountId: null, transactionId: 'txn-1', customerReference: 'CUST-42',
  screeningType: 'TRANSACTION_MONITORING', riskLevel: 'CRITICAL', status: 'ESCALATED', alertCode: 'TXN_THRESHOLD',
  alertDetail: 'Aggregate threshold exceeded', matchedEntity: null, decisionReason: 'MLRO review required',
  assignedAnalyst: 'analyst-7', decidedBy: 'analyst-4', screenedAt: '2026-09-09T10:00:00Z',
  decidedAt: '2026-09-09T10:05:00Z', createdAt: '2026-09-09T10:00:00Z', updatedAt: '2026-09-09T10:05:00Z',
}

describe('AML case contract', () => {
  it('preserves screening, alert, assignment, and decision evidence', () => {
    expect(parseAmlCases([amlCase])[0]).toMatchObject({
      customerReference: 'CUST-42', screeningType: 'TRANSACTION_MONITORING', alertCode: 'TXN_THRESHOLD',
      assignedAnalyst: 'analyst-7', decidedBy: 'analyst-4', status: 'ESCALATED',
    })
  })

  it('rejects the legacy UI-only shape and invented status', () => {
    expect(() => parseAmlCases([{ customerName: 'Example', score: 94, timestamp: '2026-09-09T10:00:00Z' }])).toThrow()
    expect(() => parseAmlCases([{ ...amlCase, status: 'PENDING_REVIEW' }])).toThrow(/status/)
  })

  it('rejects malformed audit timestamps', () => {
    expect(() => parseAmlCases([{ ...amlCase, updatedAt: 'yesterday' }])).toThrow(/updatedAt/)
  })
})
