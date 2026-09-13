import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const form = readFileSync(path.resolve(__dirname, '../app/parties/new/page.tsx'), 'utf8')
const list = readFileSync(path.resolve(__dirname, '../app/parties/page.tsx'), 'utf8')
const roles = readFileSync(path.resolve(__dirname, '../lib/auth/roles.ts'), 'utf8')

describe('party creation UI', () => {
  it('matches the party-service creator roles with a specific route and client guard', () => {
    expect(roles).toContain('"parties:create":       [ROLES.ADMIN, ROLES.OPERATOR, ROLES.KYC]')
    expect(roles).toContain("['parties:create', ['/parties/new']]")
    expect(form).toContain('<AuthGuard permission="parties:create">')
    expect(list).toContain('<Can permission="parties:create">')
  })

  it('binds all inputs to labels and announces failed submissions', () => {
    for (const id of [
      'party-type', 'party-legal-name', 'party-trading-name', 'party-tax-id',
      'party-registration-number', 'party-nationality', 'party-date-of-birth', 'party-email',
      'party-phone', 'party-address-line1', 'party-address-city', 'party-address-postal', 'party-address-country',
    ]) {
      expect(form).toContain(`htmlFor="${id}"`)
      expect(form).toContain(`id="${id}"`)
    }
    expect(form).toContain('role="alert"')
    expect(form).toContain('<Save size={13} aria-hidden="true"')
  })

  it('blocks duplicate create submissions before React can render the disabled state', () => {
    expect(form).toContain("import { useRef, useState } from 'react'")
    expect(form).toContain('const createInFlight = useRef(false)')
    expect(form).toContain('if (createInFlight.current) return')
    expect(form).toContain('createInFlight.current = true')
    expect(form).toContain('createInFlight.current = false')
  })
})
