// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { matchedRoleName } from '@/components/delegations/EffectiveAccess'
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
})
