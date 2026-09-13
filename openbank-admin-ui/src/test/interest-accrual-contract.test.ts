import { describe, expect, it } from 'vitest'
import { parseInterestAccruals, statusTone } from '@/lib/interest/interestAccrualContract'

const accrual = {
  id: 'accrual-1', accountId: 'account-1', accrualDate: '2026-09-09', accruedAmount: '10.2500',
  currency: 'czk', rate: '0.0001', dayCount: 'ACT_365', status: 'CAPITALIZING',
}

describe('interest accrual contract', () => {
  it('maps decimal payloads and all lifecycle fields', () => {
    expect(parseInterestAccruals([accrual])[0]).toEqual({
      id: 'accrual-1', accountId: 'account-1', accrualDate: '2026-09-09', accruedAmount: 10.25,
      currency: 'CZK', rate: 0.0001, dayCount: 'ACT_365', status: 'CAPITALIZING',
    })
  })

  it('rejects unknown statuses and malformed local dates', () => {
    expect(() => parseInterestAccruals([{ ...accrual, status: 'PENDING' }])).toThrow(/status/)
    expect(() => parseInterestAccruals([{ ...accrual, accrualDate: '09/09/2026' }])).toThrow(/accrualDate/)
  })

  it('keeps reversed and suspended states distinct', () => {
    expect(statusTone('REVERSED')).toBe('danger')
    expect(statusTone('SUSPENDED')).toBe('neutral')
  })
})
