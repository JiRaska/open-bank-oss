// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { hasPermission, permissionForPath } from '@/lib/auth/roles'

const root = resolve(__dirname, '..')
const read = (file: string) => readFileSync(resolve(root, file), 'utf8')

describe('PID console mirrors pid-service RBAC', () => {
  it('allows only the operator and admin roles accepted by PartyResource', () => {
    expect(hasPermission(['ROLE_ADMIN'], 'pid:view')).toBe(true)
    expect(hasPermission(['ROLE_OPERATOR'], 'pid:view')).toBe(true)
    expect(hasPermission(['ROLE_VIEWER'], 'pid:view')).toBe(false)
    expect(hasPermission(['ROLE_PAYMENTS'], 'pid:view')).toBe(false)
    expect(hasPermission(['ROLE_SUPERVISOR'], 'pid:view')).toBe(false)
  })

  it('uses the same permission at the edge, navigation, and page boundary', () => {
    expect(permissionForPath('/pid')).toBe('pid:view')
    expect(read('components/layout/Sidebar.tsx')).toMatch(/href: '\/pid'[\s\S]*permission: 'pid:view'/)
    expect(read('app/pid/page.tsx')).toContain('<AuthGuard permission="pid:view">')
  })
})
