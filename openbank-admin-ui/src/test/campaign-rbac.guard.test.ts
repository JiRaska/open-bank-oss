import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { hasPermission, permissionForPath, ROLES } from '@/lib/auth/roles'

const root = path.resolve(process.cwd(), 'src')
const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8')

describe('campaign workflow RBAC contract', () => {
  it('matches campaign-service read/write authorization', () => {
    expect(permissionForPath('/campaigns')).toBe('campaign:view')
    expect(permissionForPath('/campaigns/new')).toBe('campaign:create')
    expect(hasPermission([ROLES.AUDITOR], 'campaign:view')).toBe(true)
    expect(hasPermission([ROLES.AUDITOR], 'campaign:create')).toBe(false)
    expect(hasPermission([ROLES.COMPLIANCE], 'campaign:view')).toBe(false)
    expect(hasPermission([ROLES.OPERATOR], 'campaign:create')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'campaign:activate')).toBe(true)
  })

  it('keeps campaign authoring and lifecycle writes behind matching gates', () => {
    const list = read('app/campaigns/page.tsx')
    const create = read('app/campaigns/new/page.tsx')
    const detail = read('app/campaigns/[id]/page.tsx')
    expect(list).toContain('<AuthGuard permission="campaign:view">')
    expect(list).toContain('<Can permission="campaign:create">')
    expect(create).toContain('<AuthGuard permission="campaign:create">')
    expect(create).toContain("method: draftId ? 'PUT' : 'POST'")
    expect(detail).toContain('<AuthGuard permission="campaign:view">')
    expect(detail).toContain("'campaign:activate'")
    expect(detail).toContain("'campaign:submit'")
    expect(detail).toContain('<Can key={a} permission={actionPermission(a)}')
  })
})
