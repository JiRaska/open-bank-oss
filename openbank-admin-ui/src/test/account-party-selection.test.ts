import { describe, expect, it } from 'vitest'
import { accountPartySelection } from '@/lib/accounts/partySelection'

describe('account party selection', () => {
  it('uses the verified legal name', () => {
    expect(accountPartySelection({ id: 'a', legalName: '  Alice  ', tradingName: 'A' })).toEqual({ partyId: 'a', legalName: 'Alice' })
  })

  it('clears a stale name when only a UUID is selected', () => {
    expect(accountPartySelection({ id: 'b' })).toEqual({ partyId: 'b', legalName: '' })
  })

  it('falls back to the trading name', () => {
    expect(accountPartySelection({ id: 'c', tradingName: '  Company  ' })).toEqual({ partyId: 'c', legalName: 'Company' })
  })
})
