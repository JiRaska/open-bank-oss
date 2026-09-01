// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { capabilityIntent } from '@/lib/delegations/rolePresets'

describe('capabilityIntent', () => {
  it('explains read-only rights as viewing', () => {
    expect(capabilityIntent('ACCOUNT_READ_BALANCES')).toBe('view')
    expect(capabilityIntent('ACCOUNT_DOWNLOAD_STATEMENTS')).toBe('view')
    expect(capabilityIntent('CARD_VIEW_TRANSACTIONS')).toBe('view')
  })

  it('separates operational actions from administrative control', () => {
    expect(capabilityIntent('ACCOUNT_INITIATE_PAYMENT')).toBe('act')
    expect(capabilityIntent('SAVINGS_WITHDRAW')).toBe('act')
    expect(capabilityIntent('ACCOUNT_MANAGE_LIMITS')).toBe('manage')
    expect(capabilityIntent('DELEGATION_MANAGE')).toBe('manage')
  })
})
