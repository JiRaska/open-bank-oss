// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

describe('notifications route RBAC truthfulness', () => {
  it('uses the backend-aligned dedicated permission instead of system:view', () => {
    const roles = readFileSync(path.resolve(__dirname, '../lib/auth/roles.ts'), 'utf8')
    const sidebar = readFileSync(path.resolve(__dirname, '../components/layout/Sidebar.tsx'), 'utf8')
    const page = readFileSync(path.resolve(__dirname, '../app/notifications/page.tsx'), 'utf8')

    expect(roles).toContain("['notifications:view', ['/notifications']]")
    expect(roles).not.toContain("'/security', '/notifications', '/system'")
    expect(sidebar).toContain("href: '/notifications',     icon: Bell,         permission: 'notifications:view'")
    expect(page).toContain('<AuthGuard permission="notifications:view"><NotificationsContent /></AuthGuard>')
  })
})
