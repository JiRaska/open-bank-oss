import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(__dirname, '..')
const read = (file: string) => readFileSync(resolve(root, file), 'utf8')

describe('party PII links respect the party-service role boundary', () => {
  it('keeps route roles aligned with PartyResource and gates cross-page links', () => {
    const roles = read('lib/auth/roles.ts')
    expect(roles).toContain('"parties:view":         [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.KYC]')
    expect(read('app/kyc/page.tsx')).toMatch(/<Can permission="parties:view">[\s\S]*href=\{`\/parties\//)
    expect(read('app/onboarding/page.tsx')).toMatch(/<Can permission="parties:view">[\s\S]*href=\{`\/parties\//)
    expect(read('app/pid/page.tsx')).toMatch(/<Can permission="parties:create">[\s\S]*href="\/parties\/new"/)
    expect(read('components/entities/EntityChip.tsx')).toContain("'parties:view'")
    expect(read('components/entities/EntityChip.tsx')).toContain('canOpenParty')
  })
})
