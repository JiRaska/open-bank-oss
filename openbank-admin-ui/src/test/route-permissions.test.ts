// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { hasPermission, pathAllowedByRoles, permissionForPath } from '@/lib/auth/roles'

const APP_ROOT = 'src/app'
const PUBLIC_ROUTES = new Set(['/', '/auth/error', '/auth/forbidden', '/auth/login', '/privacy', '/r/sample'])

function pageRoutes(dir: string, route = ''): string[] {
  return readdirSync(dir).flatMap(entry => {
    const full = join(dir, entry)
    if (entry === 'page.tsx') return [route || '/']
    if (!statSync(full).isDirectory() || entry === 'api') return []
    const segment = entry.startsWith('[') ? 'sample' : entry
    return pageRoutes(full, `${route}/${segment}`)
  })
}

describe('route permission projection (ADR-0229 D3)', () => {
  it('covers every authenticated page route', () => {
    const unguarded = pageRoutes(APP_ROOT)
      .filter(route => !PUBLIC_ROUTES.has(route))
      .filter(route => permissionForPath(route) === undefined)

    expect(unguarded).toEqual([])
  })

  it('limits SCA and settlement approval review to the services\' human operator roles', () => {
    for (const path of ['/approvals/sca/sample', '/approvals/settlement/sample']) {
      expect(permissionForPath(path)).toBe('operator-approvals:decide')
    }
    expect(permissionForPath('/approvals')).toBe('approvals:view')
    for (const role of ['ROLE_OPERATOR', 'ROLE_ADMIN']) {
      expect(hasPermission([role], 'operator-approvals:decide')).toBe(true)
    }
    for (const role of ['ROLE_COMPLIANCE', 'ROLE_VIEWER', 'ROLE_PAYMENTS', 'ROLE_API', 'ROLE_SUPERVISOR']) {
      expect(hasPermission([role], 'operator-approvals:decide')).toBe(false)
    }
    expect(hasPermission([], 'operator-approvals:decide')).toBe(false)
  })

  it('uses the most specific matching prefix', () => {
    expect(permissionForPath('/dashboard')).toBe('dashboard:view')
    expect(permissionForPath('/accounts/new')).toBe('accounts:create')
    expect(permissionForPath('/cards/sample')).toBe('cards:view')
    expect(permissionForPath('/lending/compliance-packs')).toBe('lending:compliance:view')
    expect(permissionForPath('/system/config')).toBe('system:config')
  })

  it('admits the communication approvals workbench to the queue viewers OR the style checker (ADR-0285 D6)', () => {
    // ROLE_COMMS_APPROVER holds communication:style:decide but not approvals:view; the
    // OR-set keeps the bare '/approvals' guard from walling the checker out of the
    // four-eyes queue their publish pauses land in. The page itself still requires
    // communication:style:decide via its AuthGuard.
    expect(permissionForPath('/approvals/communication')).toEqual(['approvals:view', 'communication:style:decide'])
    expect(permissionForPath('/approvals/communication/x')).toEqual(['approvals:view', 'communication:style:decide'])
    // The general queue stays viewer-only: the OR-set must not loosen /approvals itself.
    expect(permissionForPath('/approvals')).toBe('approvals:view')
    expect(permissionForPath('/approvals/sca/x')).toBe('operator-approvals:decide')
    for (const role of ['ROLE_COMMS_APPROVER', 'ROLE_ADMIN']) {
      expect(pathAllowedByRoles('/approvals/communication', [role])).toBe(true)
    }
    // Operator/compliance pass the edge gate on the approvals:view half; the page's own
    // AuthGuard then names the missing communication:style:decide (pinned by the e2e spec).
    expect(pathAllowedByRoles('/approvals/communication', ['ROLE_OPERATOR'])).toBe(true)
    expect(pathAllowedByRoles('/approvals/communication', ['ROLE_COMPLIANCE'])).toBe(true)
    // Viewer lacks both halves of the OR-set: the edge gate refuses before any page render.
    expect(pathAllowedByRoles('/approvals/communication', ['ROLE_VIEWER'])).toBe(false)
  })

  it('admits the communication style editor to the maker OR the checker role (ADR-0285 D6)', () => {
    // The editor hosts both permission-gated sections, so the route guard is an OR-set,
    // not the maker permission alone — otherwise the checker could never reach the
    // publish controls this page exists to give them.
    expect(permissionForPath('/communication/edit/sample')).toEqual(['communication:style:propose', 'communication:style:decide'])
    for (const role of ['ROLE_COMMS_EDITOR', 'ROLE_COMMS_APPROVER', 'ROLE_ADMIN']) {
      expect(pathAllowedByRoles('/communication/edit/sample', [role])).toBe(true)
    }
    for (const role of ['ROLE_VIEWER', 'ROLE_COMPLIANCE', 'ROLE_OPERATOR']) {
      expect(pathAllowedByRoles('/communication/edit/sample', [role])).toBe(false)
    }
  })

  it('splits notification-template drafting from publishing (ADR-0333 D6)', () => {
    // Mirrors ManagedTemplateResource @RolesAllowed and the commstemplate-publish OPA rule:
    // operators draft, ROLE_COMMS_APPROVER publishes, and both can open the page.
    for (const role of ['ROLE_OPERATOR', 'ROLE_COMMS_APPROVER', 'ROLE_ADMIN']) {
      expect(pathAllowedByRoles('/communication/notification-templates', [role])).toBe(true)
    }
    expect(pathAllowedByRoles('/communication/notification-templates', ['ROLE_VIEWER'])).toBe(false)
    expect(hasPermission(['ROLE_OPERATOR'], 'communication:templates:publish')).toBe(false)
    expect(hasPermission(['ROLE_COMMS_APPROVER'], 'communication:templates:publish')).toBe(true)
    expect(hasPermission(['ROLE_COMMS_APPROVER'], 'communication:templates:manage')).toBe(false)
    expect(hasPermission(['ROLE_OPERATOR'], 'communication:templates:manage')).toBe(true)
  })
})
