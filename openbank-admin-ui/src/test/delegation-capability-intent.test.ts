// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { CAPABILITIES_BY_RESOURCE, assignablePresetCapabilities, capabilityIntent, isReservedOwnershipPresetName, truthfulPresetName } from '@/lib/delegations/rolePresets'

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

  it('keeps owner sharing authority out of assignable presets', () => {
    expect(CAPABILITIES_BY_RESOURCE.ACCOUNT).toContain('DELEGATION_MANAGE')
    expect(assignablePresetCapabilities('ACCOUNT')).not.toContain('DELEGATION_MANAGE')
    expect(assignablePresetCapabilities('CARD')).toEqual(CAPABILITIES_BY_RESOURCE.CARD)
  })

  it('presents historical owner-named presets as truthful delegate roles', () => {
    expect(isReservedOwnershipPresetName('  Card owner ')).toBe(true)
    expect(isReservedOwnershipPresetName('Card   owner')).toBe(true)
    expect(truthfulPresetName({ name: 'Majitel účtu', resourceType: 'ACCOUNT' })).toBe('Plný disponent účtu')
    expect(truthfulPresetName({ name: 'Majitel karty', resourceType: 'CARD' })).toBe('Plný disponent karty')
    expect(truthfulPresetName({ name: 'Účetní', resourceType: 'ACCOUNT' })).toBe('Účetní')
  })
})
