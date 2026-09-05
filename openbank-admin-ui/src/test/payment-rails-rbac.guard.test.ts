// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { hasPermission, permissionForPath } from '@/lib/auth/roles'

const root = resolve(__dirname, '..')
const read = (file: string) => readFileSync(resolve(root, file), 'utf8')

describe('payment rail consoles mirror backend read roles', () => {
  it('allows the human roles shared by SWIFT and clearing reads', () => {
    for (const role of ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_VIEWER', 'ROLE_PAYMENTS']) {
      expect(hasPermission([role], 'payment-rails:view')).toBe(true)
    }
    expect(hasPermission(['ROLE_SUPERVISOR'], 'payment-rails:view')).toBe(false)
  })

  it('uses one permission at both edge, navigation, and page boundaries', () => {
    expect(permissionForPath('/swift')).toBe('payment-rails:view')
    expect(permissionForPath('/clearing')).toBe('payment-rails:view')
    const sidebar = read('components/layout/Sidebar.tsx')
    expect(sidebar).toMatch(/href: '\/swift'[\s\S]*permission: 'payment-rails:view'/)
    expect(sidebar).toMatch(/href: '\/clearing'[\s\S]*permission: 'payment-rails:view'/)
    expect(read('app/swift/page.tsx')).toContain('<AuthGuard permission="payment-rails:view">')
    expect(read('app/clearing/page.tsx')).toContain('<AuthGuard permission="payment-rails:view">')
  })
})
