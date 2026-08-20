import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { hasPermission, permissionForPath, ROLES } from '@/lib/auth/roles'

const root = path.resolve(process.cwd(), 'src')
const page = fs.readFileSync(path.join(root, 'app/identity-cases/page.tsx'), 'utf8')

describe('identity cases RBAC contract', () => {
  it('matches PID verification-case roles and route scope', () => {
    expect(permissionForPath('/identity-cases')).toBe('identity-cases:view')
    expect(hasPermission([ROLES.ADMIN], 'identity-cases:view')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'identity-cases:decide')).toBe(true)
    expect(hasPermission([ROLES.COMPLIANCE], 'identity-cases:decide')).toBe(true)
    expect(hasPermission([ROLES.KYC_REVIEWER], 'identity-cases:view')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'identity-cases:view')).toBe(false)
  })

  it('keeps PID reads and four-eyes writes behind matching gates', () => {
    expect(page).toContain('<AuthGuard permission="identity-cases:view">')
    expect(page).toContain('<Can permission="identity-cases:decide"')
    expect(page).toContain('/api/v1/parties/cases/${c.id}/decision')
    expect(page).toContain('/api/v1/parties/cases/${c.id}/reopen')
  })
})
