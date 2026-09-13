import { describe, expect, it } from 'vitest'
import { formatClearingMoney, parseClearingBatches } from '@/lib/clearing/clearingBatchContract'

const batch = {
  id: 'b-1', batchReference: 'CLR-1', rail: 'SEPA_SCT', settlementType: 'NET', status: 'IN_CLEARING',
  totalDebit: '125.40', totalCredit: 100, netPosition: '-25.40', currency: 'eur', itemCount: 3,
  cycleId: 'cycle-1', settlementDate: '2026-09-09', settledAt: null,
  createdAt: '2026-09-09T10:00:00Z', updatedAt: '2026-09-09T10:01:00Z',
}

describe('clearing batch contract', () => {
  it('maps backend fields and preserves decimal values', () => {
    expect(parseClearingBatches([batch])[0]).toMatchObject({
      rail: 'SEPA_SCT', status: 'IN_CLEARING', totalDebit: 125.4, totalCredit: 100,
      netPosition: -25.4, currency: 'EUR', itemCount: 3,
    })
  })

  it('rejects invented statuses and legacy UI field names', () => {
    expect(() => parseClearingBatches([{ ...batch, status: 'PROCESSING' }])).toThrow(/status/)
    const { rail: _rail, totalDebit: _totalDebit, ...legacy } = batch
    expect(() => parseClearingBatches([{ ...legacy, paymentRail: 'SEPA', totalAmount: 125.4 }])).toThrow()
  })

  it('formats each batch in its declared currency', () => {
    expect(formatClearingMoney(125.4, 'EUR', 'en-GB')).toMatch(/EUR.*125\.40|125\.40.*EUR/)
  })
})
