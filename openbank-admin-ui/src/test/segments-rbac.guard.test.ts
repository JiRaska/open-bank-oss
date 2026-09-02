import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { hasPermission, permissionForPath, ROLES } from '@/lib/auth/roles'

const root = path.resolve(process.cwd(), 'src')
const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8')

describe('segments audience RBAC contract', () => {
  it('maps the route and action permissions to campaign-service authorization', () => {
    expect(permissionForPath('/segments')).toBe('campaign:view')
    expect(permissionForPath('/segments/new')).toBe('campaign:create')
    expect(hasPermission([ROLES.ADMIN], 'campaign:create')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'campaign:create')).toBe(true)
    expect(hasPermission([ROLES.AUDITOR], 'campaign:view')).toBe(true)
    expect(hasPermission([ROLES.AUDITOR], 'campaign:create')).toBe(false)
    expect(hasPermission([ROLES.COMPLIANCE], 'campaign:view')).toBe(false)
  })

  it('keeps audience writes behind matching Can/AuthGuard gates', () => {
    const list = read('app/segments/page.tsx')
    const create = read('app/segments/new/page.tsx')
    expect(list).toContain('<AuthGuard permission="campaign:view">')
    expect(list).toContain('<Can permission="campaign:create">')
    expect(list).toContain('<Can permission="campaign:submit"')
    expect(list).toContain('<Can permission="campaign:activate"')
    expect(create).toContain('<AuthGuard permission="campaign:create">')
    expect(create).toContain('method: \'POST\'')
  })
})
