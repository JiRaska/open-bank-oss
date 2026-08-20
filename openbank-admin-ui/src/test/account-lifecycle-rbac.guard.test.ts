import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(__dirname, '..')
const page = readFileSync(resolve(root, 'app/accounts/[id]/page.tsx'), 'utf8')
const roles = readFileSync(resolve(root, 'lib/auth/roles.ts'), 'utf8')

describe('account lifecycle controls respect mutation permissions', () => {
  it('keeps lifecycle visibility aligned with the permission matrix', () => {
    expect(roles).toContain('"accounts:freeze":          [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE]')
    expect(roles).toContain('"accounts:close":           [ROLES.ADMIN]')
    expect(page).toMatch(/<Can permission="accounts:freeze">[\s\S]*requestAction\('freeze'\)/)
    expect(page).toMatch(/<Can permission="accounts:freeze">[\s\S]*requestAction\('unfreeze'\)/)
    expect(page).toMatch(/<Can permission="accounts:close">[\s\S]*requestAction\('close'\)/)
  })

  it('keeps lifecycle controls named and explicit buttons', () => {
    expect(page).toContain('aria-label={t(\'Zmrazit účet\', \'Freeze account\')}')
    expect(page).toContain('aria-label={t(\'Odmrazit účet\', \'Unfreeze account\')}')
    expect(page).toContain('aria-label={t(\'Zrušit účet\', \'Close account\')}')
    expect(page).toContain('aria-busy={acting}')
    expect(page).toContain('type="button"')
  })
})
