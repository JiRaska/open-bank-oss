import { describe, expect, it } from 'vitest'
import { formatLocalDate, formatMinorUnits, parseStandingOrders } from '@/lib/standing-orders/standingOrderContract'

const order = {
  id: '11111111-1111-1111-1111-111111111111',
  partyId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  debtorAccountId: '22222222-2222-2222-2222-222222222222',
  creditorIban: 'CZ6508000000192000145399',
  creditorName: 'Supplier SE',
  status: 'PAUSED',
  frequency: 'BIWEEKLY',
  paymentType: 'DOMESTIC',
  amountMinorUnits: 725001,
  amount: 7250.01,
  currency: 'CZK',
  nextExecutionDate: '2026-09-15',
  remittanceInfo: 'Invoice 42',
  executionCount: 3,
  createdAt: '2026-01-01T08:00:00Z',
  updatedAt: '2026-09-01T08:00:00Z',
}

describe('standing-order read contract', () => {
  it('uses backend lifecycle, schedule, payment type and authoritative minor units', () => {
    expect(parseStandingOrders([order])).toEqual([expect.objectContaining({
      status: 'PAUSED',
      frequency: 'BIWEEKLY',
      paymentType: 'DOMESTIC',
      amountMinorUnits: 725001,
      remittanceInfo: 'Invoice 42',
    })])
  })

  it('rejects invented legacy states and malformed money or dates', () => {
    expect(() => parseStandingOrders([{ ...order, status: 'SUSPENDED' }])).toThrow(/status/)
    expect(() => parseStandingOrders([{ ...order, amountMinorUnits: 72.5 }])).toThrow(/amount/)
    expect(() => parseStandingOrders([{ ...order, nextExecutionDate: '2026-02-30' }])).toThrow(/date/)
  })

  it('formats minor units exactly and keeps LocalDate stable across time zones', () => {
    expect(formatMinorUnits(725001, 'en-GB')).toBe('7,250.01')
    expect(formatLocalDate('2026-09-15', 'en-GB')).toBe('15 Sept 2026')
  })
})
