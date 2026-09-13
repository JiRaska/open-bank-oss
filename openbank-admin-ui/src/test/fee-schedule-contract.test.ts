import { describe, expect, it } from 'vitest'
import { describeWaiverRule, parseFeeSchedule } from '@/lib/fees/feeScheduleContract'

const fee = {
  id: 'product:fee', code: 'CURRENT_MONTHLY', name: 'Monthly fee', type: 'ACCOUNT', amount: 4.5,
  currency: 'eur', frequency: 'MONTHLY', description: null, waivable: true,
  waiveCondition: 'Balance > 50000 EUR', waiverEvaluable: true,
  waiverRule: { attribute: 'BALANCE', operator: '>', threshold: '50000', thresholdCurrency: 'EUR', textValue: null },
  productId: 'product', productCode: 'CURRENT', productName: 'Current account', status: 'ACTIVE',
  updatedAt: '2026-09-09T10:00:00Z',
}

describe('fee schedule contract', () => {
  it('preserves executable waiver evidence', () => {
    const parsed = parseFeeSchedule([fee])[0]
    expect(parsed.currency).toBe('EUR')
    expect(describeWaiverRule(parsed.waiverRule!)).toBe('balance > 50000 EUR')
  })

  it('rejects contradictory or unknown policy evidence', () => {
    expect(() => parseFeeSchedule([{ ...fee, waiverRule: null }])).toThrow(/Inconsistent/)
    expect(() => parseFeeSchedule([{ ...fee, status: 'PUBLISHED' }])).toThrow(/status/)
  })

  it('accepts a manual-only waiver without inventing an executable rule', () => {
    expect(parseFeeSchedule([{ ...fee, waiverEvaluable: false, waiverRule: null }])[0]).toMatchObject({ waivable: true, waiverEvaluable: false })
  })
})
