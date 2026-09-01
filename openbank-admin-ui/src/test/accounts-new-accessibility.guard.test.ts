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
    expect(page).toContain("import { PartySearch, type PartyHit } from '@/components/party/PartySearch'")
    expect(page).toContain('<PartySearch')
    expect(page).toContain('onSelect={selectParty}')
    expect(page).toContain('accountPartySelection(party)')
    expect(page).toContain('partyId: selection.partyId')
    expect(page).toContain('legalName: selection.legalName')
    expect(page).toContain('accountApi.open({')
    expect(page).toContain('partyId:     form.partyId.trim()')
    expect(page).toContain('legalName:   form.legalName.trim()')
  })

  it('keeps one account-opening request and its idempotency key stable across a retry', () => {
    expect(page).toContain("import { useEffect, useRef, useState } from 'react'")
    expect(page).toContain('const openingInFlight = useRef(false)')
    expect(page).toContain('const idempotencyKey = useRef<string | null>(null)')
    expect(page).toContain('if (openingInFlight.current) return')
    expect(page).toContain('idempotencyKey.current ??= crypto.randomUUID()')
    expect(page).toContain('}, stableIdempotencyKey)')
    expect(page).toContain('openingInFlight.current = false')
  })
})
