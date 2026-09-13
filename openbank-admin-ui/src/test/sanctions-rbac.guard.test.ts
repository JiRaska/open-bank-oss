import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(__dirname, '..')
const page = readFileSync(resolve(root, 'app/sanctions/page.tsx'), 'utf8')
const roles = readFileSync(resolve(root, 'lib/auth/roles.ts'), 'utf8')
const sidebar = readFileSync(resolve(root, 'components/layout/Sidebar.tsx'), 'utf8')

describe('sanctions UI permissions mirror sanctions-service', () => {
  it('keeps route/read access to backend viewer-tier roles', () => {
    expect(roles).toContain('"sanctions:view":       [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER]')
    expect(page).toContain('<AuthGuard permission="sanctions:view">')
    expect(sidebar).toContain("href: '/sanctions'")
    expect(sidebar).toContain("permission: 'sanctions:view'")
  })

  it('does not expose screening, list writes or review to read-only users', () => {
    expect(roles).toContain('"sanctions:screen":     [ROLES.ADMIN, ROLES.OPERATOR]')
    expect(roles).toContain('"sanctions:manage":     [ROLES.ADMIN, ROLES.OPERATOR]')
    expect(roles).toContain('"sanctions:review":     [ROLES.ADMIN, ROLES.OPERATOR]')
    expect(page).toContain('<Can permission="sanctions:screen"')
    expect(page).toContain('<Can permission="sanctions:manage">')
    expect(page).toContain('<Can permission="sanctions:review"')
    expect(page).toContain('Sanctions decisions are available to operators and administrators only.')
  })
})
