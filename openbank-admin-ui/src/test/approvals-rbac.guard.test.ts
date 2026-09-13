import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { hasPermission, permissionForPath, ROLES } from '@/lib/auth/roles'

const page = fs.readFileSync(path.join(process.cwd(), 'src/app/approvals/page.tsx'), 'utf8')
const sidebar = fs.readFileSync(path.join(process.cwd(), 'src/components/layout/Sidebar.tsx'), 'utf8')
const header = fs.readFileSync(path.join(process.cwd(), 'src/components/layout/Header.tsx'), 'utf8')

describe('approval inbox RBAC truthfulness', () => {
  it('matches ProposalResource roles and protects the actionable workflow', () => {
    expect(hasPermission([ROLES.ADMIN], 'approvals:view')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'approvals:view')).toBe(true)
    expect(hasPermission([ROLES.COMPLIANCE], 'approvals:view')).toBe(true)
    expect(hasPermission([ROLES.DEMO], 'approvals:view')).toBe(false)
    expect(hasPermission([ROLES.COMPLIANCE], 'agent:decide')).toBe(true)
    expect(hasPermission([ROLES.DEMO], 'agent:decide')).toBe(false)
    expect(permissionForPath('/approvals')).toBe('approvals:view')
    expect(page).toContain('<AuthGuard permission="approvals:view">')
    expect(page).toContain('<Can permission="agent:decide"')
    expect(page).toContain('Decision access requires agent authorization.')
    expect(sidebar).toContain("href: '/approvals', icon: ClipboardCheck, permission: 'approvals:view'")
    expect(header).toContain("hasPermission(roles, 'approvals:view')")
  })
})
