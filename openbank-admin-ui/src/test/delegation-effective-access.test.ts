// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { grantConditions, grantResourcePresentation, isEffectiveAccessPayload, matchedRoleName } from '@/components/delegations/EffectiveAccess'
import type { Grant } from '@/components/delegations/GrantView'
import type { RolePreset } from '@/lib/delegations/rolePresets'

const grant = { resourceType: 'ACCOUNT', capabilities: ['ACCOUNT_READ_BALANCES', 'ACCOUNT_READ_TRANSACTIONS'] } as Grant
const presets = [{ id: 'p1', name: 'Účetní', description: '', resourceType: 'ACCOUNT', capabilities: ['ACCOUNT_READ_TRANSACTIONS', 'ACCOUNT_READ_BALANCES'] }] as RolePreset[]

describe('effective access role matching', () => {
  it('matches a preset by resource and exact capability set regardless of ordering', () => {
    expect(matchedRoleName(grant, presets, 'cs')).toBe('Účetní')
  })

  it('does not overstate a grant that only partly resembles a preset', () => {
    expect(matchedRoleName({ ...grant, capabilities: ['ACCOUNT_READ_BALANCES'] }, presets, 'cs')).toBe('Vlastní kombinace práv')
  })

  it('rejects a legacy grant payload instead of crashing the customer console', () => {
    expect(isEffectiveAccessPayload({ ...grant, id: 'g1' })).toBe(false)
    expect(isEffectiveAccessPayload({ accounts: [], cards: [], grants: [], presets: [], resourceDetails: [], sources: { accounts: 'ok' } })).toBe(true)
  })

  it('explains the concrete account behind a delegation instead of showing only its UUID', () => {
    const details = [{ key: 'ACCOUNT:account-1', resourceType: 'ACCOUNT', resourceId: 'account-1', state: 'ok', detail: { accountNumber: 'CZ1234567890', currencyCode: 'CZK', status: 'ACTIVE' } }] as never
    expect(grantResourcePresentation({ ...grant, resourceId: 'account-1' }, details, 'cs')).toEqual({
      label: 'Účet •••• 7890',
      meta: 'CZK · ACTIVE',
    })
  })

  it('explains financial and approval guardrails for an active operation role', () => {
    const conditions = grantConditions({
      ...grant,
      capabilities: ['ACCOUNT_INITIATE_PAYMENT'],
      approvalPolicy: 'SOLO',
      perTransactionLimit: { amount: 5000, currency: 'CZK' },
      dailyLimit: null,
      monthlyLimit: { amount: 20000, currency: 'CZK' },
      validTo: '2026-12-31T12:00:00Z',
    }, 'cs')
    expect(conditions).toEqual([
      { label: 'Platnost', value: 'do 31. 12. 2026' },
      { label: 'Schválení', value: 'samostatně' },
      { label: 'Jedna operace', value: '5 000 CZK' },
      { label: 'Denně', value: 'bez limitu' },
      { label: 'Měsíčně', value: '20 000 CZK' },
    ])
  })

  it('does not imply financial limits for a read-only role', () => {
    expect(grantConditions({ ...grant, validTo: null }, 'en')).toEqual([{ label: 'Validity', value: 'no end date' }])
  })
})
