import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(__dirname, '..')
const page = readFileSync(resolve(root, 'app/lending/compliance-packs/page.tsx'), 'utf8')
const roles = readFileSync(resolve(root, 'lib/auth/roles.ts'), 'utf8')
const sidebar = readFileSync(resolve(root, 'components/layout/Sidebar.tsx'), 'utf8')

describe('lending compliance-pack UI permissions mirror service roles', () => {
  it('keeps active-pack reads available to the service-authorized lending roles', () => {
    expect(roles).toContain('"lending:compliance:view":    [ROLES.ADMIN, ROLES.COMPLIANCE, ROLES.CREDIT_RISK, ROLES.LENDING_OFFICER]')
    expect(page).toContain('<AuthGuard permission="lending:compliance:view">')
    expect(sidebar).toContain("href: '/lending/compliance-packs'")
    expect(sidebar).toContain("permission: 'lending:compliance:view'")
  })

  it('gates maker/checker writes to compliance principals and administrators', () => {
    expect(roles).toContain('"lending:compliance:propose": [ROLES.ADMIN, ROLES.COMPLIANCE]')
    expect(roles).toContain('"lending:compliance:decide":  [ROLES.ADMIN, ROLES.COMPLIANCE]')
    expect(page).toContain('<Can permission="lending:compliance:propose">')
    expect(page).toContain('<Can permission="lending:compliance:decide"')
    expect(page).toContain('type="button" className="btn btn-primary"')
    expect(page).toContain('type="button" className="btn btn-secondary"')
  })
})
