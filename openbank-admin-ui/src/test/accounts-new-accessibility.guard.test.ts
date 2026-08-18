import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/accounts/new/page.tsx'), 'utf8')

describe('new account form accessibility', () => {
  it('binds every account-opening input to its label and exposes validation feedback', () => {
    for (const id of ['account-party-id', 'account-product-id', 'account-legal-name', 'account-type', 'account-currency']) {
      expect(page).toContain(`htmlFor=\"${id}\"`)
      expect(page).toContain(`id=\"${id}\"`)
    }
    expect(page).toContain('aria-invalid={Boolean(errors.partyId)}')
    expect(page).toContain('aria-invalid={Boolean(errors.productId)}')
    expect(page).toContain('aria-invalid={Boolean(errors.legalName)}')
    expect(page).toContain('role="alert"')
  })

  it('does not expose decorative form icons to assistive technology', () => {
    expect(page).toContain('<AlertCircle size={14} aria-hidden="true"')
    expect(page).toContain('<Save size={13} aria-hidden="true"')
  })

  it('requires account-creation permission before rendering the form', () => {
    expect(page).toContain('<AuthGuard permission="accounts:create">')
  })

  it('resolves an existing party before the money-path submit', () => {
    expect(page).toContain("import { PartySearch, partyDisplayName, type PartyHit } from '@/components/party/PartySearch'")
    expect(page).toContain('<PartySearch')
    expect(page).toContain('onSelect={selectParty}')
    expect(page).toContain('partyId: party.id')
    expect(page).toContain('legalName: partyDisplayName(party)')
    expect(page).toContain('accountApi.open({')
    expect(page).toContain('partyId:     form.partyId.trim()')
    expect(page).toContain('legalName:   form.legalName.trim()')
  })
})
