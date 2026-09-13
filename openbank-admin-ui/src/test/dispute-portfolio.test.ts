// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import {
  disputeDaysRemaining,
  isDisputeSlaBreached,
  isTerminalDispute,
  parseDisputeList,
  type DisputeRecord,
} from '@/lib/disputes/disputePortfolio'

const dispute: DisputeRecord = {
  id: 'd-1', reference: 'DSP-1', disputeType: 'UNAUTHORIZED', status: 'OPEN',
  accountId: 'account-1', transactionId: 'transaction-1', amount: 42.5, currency: 'EUR',
  resolutionDeadline: '2026-09-09', createdAt: '2026-08-01T10:00:00Z',
}

describe('dispute portfolio contract', () => {
  it('accepts the service-owned field and status vocabulary', () => {
    expect(parseDisputeList([dispute])).toEqual([dispute])
    expect(isTerminalDispute('RESOLVED_CUSTOMER')).toBe(true)
    expect(isTerminalDispute('RESOLVED_MERCHANT')).toBe(true)
    expect(isTerminalDispute('WITHDRAWN')).toBe(true)
    expect(isTerminalDispute('ESCALATED')).toBe(false)
  })

  it.each([
    [{ ...dispute, reference: undefined }],
    [{ ...dispute, status: 'CLOSED' }],
    [{ ...dispute, resolutionDeadline: '2026-02-30' }],
    [{ ...dispute, amount: Number.NaN }],
    [{ ...dispute, currency: 'euro' }],
  ])('rejects a payload that cannot support truthful rendering', invalid => {
    expect(() => parseDisputeList(invalid)).toThrow('invalid dispute portfolio payload')
  })

  it('treats the LocalDate deadline as inclusive and never breaches terminal cases', () => {
    const today = new Date(2026, 8, 9, 18, 30)
    expect(disputeDaysRemaining('2026-09-09', today)).toBe(0)
    expect(isDisputeSlaBreached(dispute, today)).toBe(false)
    expect(isDisputeSlaBreached({ ...dispute, resolutionDeadline: '2026-09-08' }, today)).toBe(true)
    expect(isDisputeSlaBreached({ ...dispute, status: 'WITHDRAWN', resolutionDeadline: '2026-09-08' }, today)).toBe(false)
  })
})
