import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { hasPermission, permissionForPath, ROLES } from '@/lib/auth/roles'

const page = fs.readFileSync(path.resolve(process.cwd(), 'src/app/system/agent/page.tsx'), 'utf8')

describe('agent MCP RBAC contract', () => {
  it('matches MCP endpoint roles and overrides broad system route access', () => {
    expect(permissionForPath('/system/agent')).toBe('agent:view')
    expect(hasPermission([ROLES.ADMIN], 'agent:view')).toBe(true)
    expect(hasPermission([ROLES.COMPLIANCE], 'agent:view')).toBe(true)
    expect(hasPermission([ROLES.DEMO], 'agent:view')).toBe(false)
    expect(hasPermission([ROLES.OPERATOR], 'agent:execute')).toBe(true)
  })

  it('protects MCP reads and tool execution independently', () => {
    expect(page).toContain('<AuthGuard permission="agent:view">')
    expect(page).toContain('<Can permission="agent:execute"')
    expect(page).toContain("method: 'POST'")
    expect(page).toContain("method, params")
  })
})
