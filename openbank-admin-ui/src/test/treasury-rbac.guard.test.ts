// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0315 / #10618: the Treasury section's UI grants must mirror TreasuryResource's own
// @RolesAllowed, every page must be gated both by AuthGuard and by the edge route table, and no
// staff role outside the treasury desk (and ADMIN, for reads) may open any of it.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { hasPermission, permissionForPath, ROLES, type Permission } from '@/lib/auth/roles'
import { personaForRoles, workspaceFor } from '@/lib/auth/persona'

const root = resolve(__dirname, '..')
const read = (p: string) => readFileSync(resolve(root, p), 'utf8')
const sidebar = read('components/layout/Sidebar.tsx')
const resource = read('../../openbank-treasury-service/src/main/kotlin/com/openbank/treasury/infrastructure/rest/TreasuryResource.kt')

const PAGES: [string, string, Permission][] = [
  ['app/treasury/deals/page.tsx', '/treasury/deals', 'treasury:view'],
  ['app/treasury/deals/[id]/page.tsx', '/treasury/deals/0b7c', 'treasury:view'],
  ['app/treasury/deals/new/page.tsx', '/treasury/deals/new', 'treasury:deal:create'],
  ['app/treasury/approvals/page.tsx', '/treasury/approvals', 'treasury:deal:approve'],
  ['app/treasury/counterparties/page.tsx', '/treasury/counterparties', 'treasury:view'],
  ['app/treasury/positions/page.tsx', '/treasury/positions', 'treasury:view'],
]

const OUTSIDE_THE_DESK = [
  ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.PAYMENTS, ROLES.AUDITOR, ROLES.SUPERVISOR,
  ROLES.CREDIT_RISK, ROLES.LENDING_OFFICER, ROLES.RISK, ROLES.FINANCE,
]

/** The CONSTANT names a Kotlin @RolesAllowed carries, resolved: Roles.ADMIN / DEALER / APPROVER. */
function resolveRoles(args: string): string[] {
  const consts: Record<string, string> = Object.fromEntries(
    [...resource.matchAll(/const val ([A-Z_]+) = "(ROLE_[A-Z_]+)"/g)].map(m => [m[1], m[2]]),
  )
  return args.split(',').map(a => a.trim()).filter(Boolean).map(a => {
    if (a.startsWith('Roles.')) return `ROLE_${a.slice('Roles.'.length)}`
    if (a.startsWith('"')) return a.replace(/"/g, '')
    return consts[a] ?? a
  }).sort()
}

/** The @RolesAllowed on the method whose @Path is [path] (and, for /deals, the POST one). */
function methodRoles(path: string, verb: 'GET' | 'POST'): string[] | null {
  const re = new RegExp(`@${verb}\\s*\\n\\s*@Path\\("${path.replace(/[{}]/g, m => `\\${m}`)}"\\)\\s*\\n\\s*@RolesAllowed\\(([^)]*)\\)`)
  const m = resource.match(re)
  return m ? resolveRoles(m[1]) : null
}

const granted = (p: Permission) => Object.values(ROLES).filter(r => hasPermission([r], p)).sort()

describe('treasury — route and page gates', () => {
  it.each(PAGES)('%s is gated by AuthGuard and the edge route table on %s', (file, path, permission) => {
    expect(read(file)).toContain(`<AuthGuard permission="${permission}">`)
    expect(permissionForPath(path)).toBe(permission)
  })

  it.each(PAGES)('%s is denied to every staff role outside the treasury desk', (_file, path) => {
    const permission = permissionForPath(path)!
    for (const role of OUTSIDE_THE_DESK) expect(hasPermission([role], permission), `${role} -> ${path}`).toBe(false)
    expect(hasPermission([], permission)).toBe(false)
  })

  it('a dealer gets the form but not the approval inbox; an approver the inbox but not the form', () => {
    expect(hasPermission([ROLES.TREASURY_DEALER], 'treasury:deal:create')).toBe(true)
    expect(hasPermission([ROLES.TREASURY_DEALER], 'treasury:deal:approve')).toBe(false)
    expect(hasPermission([ROLES.TREASURY_APPROVER], 'treasury:deal:approve')).toBe(true)
    expect(hasPermission([ROLES.TREASURY_APPROVER], 'treasury:deal:create')).toBe(false)
  })

  it('nav entries carry the same permissions as their pages', () => {
    for (const [, path, permission] of PAGES.filter(p => !p[1].includes('0b7c'))) {
      expect(sidebar).toMatch(new RegExp(`href: '${path}',\\s+icon: \\w+,\\s+permission: '${permission}'`))
    }
  })

  it('treasury staff land in the treasury persona, whose shortcuts stay inside each role’s grant', () => {
    for (const role of [ROLES.TREASURY_DEALER, ROLES.TREASURY_APPROVER]) {
      expect(personaForRoles([role])).toBe('treasury')
      expect(hasPermission([role], 'dashboard:view')).toBe(true)
    }
    const links = workspaceFor('treasury')
    expect(links.length).toBeGreaterThan(0)
    for (const link of links) expect(permissionForPath(link.href)).toBe(link.permission)
  })
})

describe('treasury — UI grants never exceed the service', () => {
  it('reads: the UI set is exactly the class-level @RolesAllowed', () => {
    const cls = resource.match(/@RolesAllowed\(([^)]*)\)\s*\n(?:@Suppress[^\n]*\n)?class TreasuryResource/)
    expect(cls, 'class-level @RolesAllowed not found').toBeTruthy()
    expect(granted('treasury:view')).toEqual(resolveRoles(cls![1]))
  })

  it('draft: only the POST /deals roles — ADMIN is not admitted by the service, so not by the UI', () => {
    expect(methodRoles('/deals', 'POST')).toEqual([ROLES.TREASURY_DEALER])
    expect(methodRoles('/deals/{id}/submit', 'POST')).toEqual([ROLES.TREASURY_DEALER])
    expect(granted('treasury:deal:create')).toEqual([ROLES.TREASURY_DEALER])
  })

  it('approve and every approver-only step share one role set with the UI grant', () => {
    for (const step of ['approve', 'reject', 'settle', 'mature', 'reverse']) {
      expect(methodRoles(`/deals/{id}/${step}`, 'POST'), step).toEqual([ROLES.TREASURY_APPROVER])
    }
    expect(granted('treasury:deal:approve')).toEqual([ROLES.TREASURY_APPROVER])
  })

  it('cancel admits both desk roles, as the service does', () => {
    expect(methodRoles('/deals/{id}/cancel', 'POST')).toEqual([ROLES.TREASURY_APPROVER, ROLES.TREASURY_DEALER].sort())
    expect(granted('treasury:deal:cancel')).toEqual([ROLES.TREASURY_APPROVER, ROLES.TREASURY_DEALER].sort())
  })
})
