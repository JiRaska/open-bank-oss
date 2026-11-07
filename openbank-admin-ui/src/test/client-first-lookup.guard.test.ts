import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = (name: string) => readFileSync(path.resolve(__dirname, `../app/${name}/page.tsx`), 'utf8')

describe('client-first lookup (no UUID typing as the primary flow)', () => {
  it.each(['kyc', 'loyalty', 'transactions', 'audit', 'document-templates'])('%s uses PartySearch', name => {
    expect(page(name)).toContain('<PartySearch')
  })

  it('kyc has no raw party id input', () => {
    expect(page('kyc')).not.toContain('kyc-party-id')
  })

  it.each(['kyc', 'loyalty', 'transactions', 'accounts', 'audit', 'document-templates'])('%s shows no UUID wording in placeholders', name => {
    expect(page(name)).not.toMatch(/placeholder=\{?t?\(?['"][^'"]*UUID/)
  })

  it('transactions lists the client accounts (bounded) and validates a typed IBAN', () => {
    const src = page('transactions')
    expect(src).toContain('/api/v1/accounts?partyId=')
    expect(src).toContain('isValidIban(iban)')
  })

  it('audit resolves an IBAN to the account id', () => {
    const src = page('audit')
    expect(src).toContain('/api/v1/accounts/iban/')
    expect(src).toContain('Technical ID (advanced)')
  })

  it('documents list by party via the existing partyRef endpoint', () => {
    expect(page('document-templates')).toContain('partyRef=')
  })
})
