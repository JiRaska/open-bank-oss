// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { hasPermission, permissionForPath, ROLES } from '@/lib/auth/roles'

const root = path.resolve(process.cwd(), 'src')
const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8')

describe('MGM referral program RBAC contract', () => {
  it('routes the referrals pages by create permission while the list stays on campaign:view', () => {
    expect(permissionForPath('/campaigns/referrals')).toBe('campaign:view')
    expect(permissionForPath('/campaigns/referrals/new')).toBe('referral:program:create')
  })

  it('lets marketing author a draft but never publish it', () => {
    expect(hasPermission([ROLES.MARKETING], 'referral:program:create')).toBe(true)
    expect(hasPermission([ROLES.MARKETING], 'referral:program:publish')).toBe(false)
    expect(hasPermission([ROLES.OPERATOR], 'referral:program:create')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'referral:program:publish')).toBe(true)
    expect(hasPermission([ROLES.VIEWER], 'referral:program:create')).toBe(false)
    expect(hasPermission([ROLES.VIEWER], 'referral:program:publish')).toBe(false)
  })

  it('keeps the authoring entry point and publish action behind their gates', () => {
    const list = read('app/campaigns/referrals/page.tsx')
    const create = read('app/campaigns/referrals/new/page.tsx')
    expect(list).toContain('<AuthGuard permission="campaign:view">')
    expect(list).toContain('<Can permission="referral:program:create">')
    expect(list).toContain('<Can permission="referral:program:publish">')
    expect(list).toContain("fetch(`/api/referral-programs/${encodeURIComponent(program.id)}/publish`")
    expect(list).toContain("fetch('/api/referral-programs/drafts'")
    expect(create).toContain('<AuthGuard permission="referral:program:create">')
    expect(create).toContain("fetch('/api/referral-programs'")
  })
})
