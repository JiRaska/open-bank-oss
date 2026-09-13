// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { hasPermission, permissionForPath } from '@/lib/auth/roles'

const root = resolve(__dirname, '..')
const read = (file: string) => readFileSync(resolve(root, file), 'utf8')

describe('Interest console mirrors InterestResource accrual-read roles', () => {
  it('allows only the viewer, operator and admin roles accepted by InterestResource', () => {
    expect(hasPermission(['ROLE_ADMIN'], 'interest:view')).toBe(true)
    expect(hasPermission(['ROLE_OPERATOR'], 'interest:view')).toBe(true)
    expect(hasPermission(['ROLE_VIEWER'], 'interest:view')).toBe(true)
    expect(hasPermission(['ROLE_PAYMENTS'], 'interest:view')).toBe(false)
    expect(hasPermission(['ROLE_SUPERVISOR'], 'interest:view')).toBe(false)
  })

  it('uses the same permission at the edge, navigation, and page boundary', () => {
    expect(permissionForPath('/interest')).toBe('interest:view')
    expect(read('components/layout/Sidebar.tsx')).toMatch(/href: '\/interest'[\s\S]*permission: 'interest:view'/)
    expect(read('app/interest/page.tsx')).toContain('<AuthGuard permission="interest:view">')
  })
})
