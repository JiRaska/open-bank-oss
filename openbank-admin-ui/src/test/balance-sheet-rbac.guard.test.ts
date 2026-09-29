// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// #10618: the "Balance sheet & risk" section's UI grants must mirror the backends' own
// @RolesAllowed, and every page must be denied to a user without the department roles.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { hasPermission, permissionForPath, PERMISSIONS, ROLES, type Permission } from '@/lib/auth/roles'

const root = resolve(__dirname, '..')
const read = (p: string) => readFileSync(resolve(root, p), 'utf8')
const sidebar = read('components/layout/Sidebar.tsx')
const riskResource = read('../../openbank-risk-engine/src/main/kotlin/com/openbank/risk/infrastructure/rest/RiskResource.kt')
const curveResource = read('../../openbank-risk-engine/src/main/kotlin/com/openbank/risk/infrastructure/rest/CurveSetResource.kt')
const backfillResource = read('../../openbank-lending-service/src/main/kotlin/com/openbank/lending/infrastructure/rest/LedgerBackfillResource.kt')

const PAGES: [string, string, string][] = [
  ['app/balance-sheet/snapshots/page.tsx', '/balance-sheet/snapshots', 'balance-sheet:view'],
  ['app/balance-sheet/snapshots/[id]/page.tsx', '/balance-sheet/snapshots/sample', 'balance-sheet:view'],
  ['app/balance-sheet/snapshots/[id]/irrbb/page.tsx', '/balance-sheet/snapshots/sample/irrbb', 'balance-sheet:view'],
  ['app/balance-sheet/snapshots/[id]/liquidity/page.tsx', '/balance-sheet/snapshots/sample/liquidity', 'balance-sheet:view'],
  ['app/balance-sheet/snapshots/[id]/capital/page.tsx', '/balance-sheet/snapshots/sample/capital', 'balance-sheet:view'],
  ['app/balance-sheet/curve-sets/page.tsx', '/balance-sheet/curve-sets', 'balance-sheet:view'],
  ['app/balance-sheet/curve-sets/[id]/page.tsx', '/balance-sheet/curve-sets/sample', 'balance-sheet:view'],
  ['app/balance-sheet/ledger-backfill/page.tsx', '/balance-sheet/ledger-backfill', 'ledger-backfill:view'],
]

/** Roles named by the first class-level / method-level @RolesAllowed, `Roles.X` resolved to `ROLE_X`. */
function rolesAllowed(source: string, nth = 0): string[] {
  const all = [...source.matchAll(/@RolesAllowed\(([^)]*)\)/g)]
  const args = all[nth]?.[1] ?? ''
  return [...args.matchAll(/"(ROLE_[A-Z_]+)"|Roles\.([A-Z_]+)/g)]
    .map(m => m[1] ?? `ROLE_${m[2]}`)
    .sort()
}

const HUMAN_STAFF_WITHOUT_DEPARTMENT = [
  ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.PAYMENTS, ROLES.AUDITOR, ROLES.SUPERVISOR,
  ROLES.CREDIT_RISK, ROLES.LENDING_OFFICER, ROLES.TREASURY_DEALER, ROLES.TREASURY_APPROVER,
]

describe('balance sheet & risk — route and page gates', () => {
  it.each(PAGES)('%s is gated by AuthGuard and the edge route table on %s', (file, path, permission) => {
    expect(read(file)).toContain(`<AuthGuard permission="${permission}">`)
    expect(permissionForPath(path)).toBe(permission)
  })

  it.each(PAGES)('%s is denied to every staff role outside the department set', (_file, path) => {
    const permission = permissionForPath(path)!
    for (const role of HUMAN_STAFF_WITHOUT_DEPARTMENT) {
      expect(hasPermission([role], permission), `${role} -> ${path}`).toBe(false)
    }
    expect(hasPermission([], permission)).toBe(false)
  })

  it('risk sees snapshots and curves but not the finance-only backfill; finance sees all three', () => {
    expect(hasPermission([ROLES.RISK], 'balance-sheet:view')).toBe(true)
    expect(hasPermission([ROLES.RISK], 'ledger-backfill:view')).toBe(false)
    expect(hasPermission([ROLES.FINANCE], 'balance-sheet:view')).toBe(true)
    expect(hasPermission([ROLES.FINANCE], 'ledger-backfill:view')).toBe(true)
  })

  it('offers the create/upload forms to risk and admin only — never to finance', () => {
    for (const p of ['balance-sheet:snapshot:create', 'balance-sheet:curves:upload'] as const) {
      expect(hasPermission([ROLES.RISK], p)).toBe(true)
      expect(hasPermission([ROLES.ADMIN], p)).toBe(true)
      expect(hasPermission([ROLES.FINANCE], p)).toBe(false)
      expect(hasPermission([ROLES.OPERATOR], p)).toBe(false)
    }
  })

  it('treasury roles unlock only the Treasury section and the workspace landing (ADR-0315)', () => {
    for (const role of [ROLES.TREASURY_DEALER, ROLES.TREASURY_APPROVER]) {
      const unlocked = (Object.keys(PERMISSIONS) as Permission[]).filter(p => hasPermission([role], p))
      expect(unlocked.every(p => p === 'dashboard:view' || p.startsWith('treasury:')), `${role}: ${unlocked.join(',')}`).toBe(true)
    }
  })

  it('nav entries carry the same permissions as their pages', () => {
    expect(sidebar).toContain("href: '/balance-sheet/snapshots',       icon: Scale,       permission: 'balance-sheet:view'")
    expect(sidebar).toContain("href: '/balance-sheet/curve-sets',      icon: TrendingUp,  permission: 'balance-sheet:view'")
    expect(sidebar).toContain("href: '/balance-sheet/ledger-backfill', icon: BookOpen,    permission: 'ledger-backfill:view'")
  })
})

describe('balance sheet & risk — UI grants never exceed the service', () => {
  it('risk-engine reads: the UI set is the class-level @RolesAllowed minus machine and operator roles', () => {
    for (const source of [riskResource, curveResource]) {
      const service = rolesAllowed(source, 0)
      expect(service).toEqual(['ROLE_ADMIN', 'ROLE_API', 'ROLE_FINANCE', 'ROLE_OPERATOR', 'ROLE_RISK'])
      for (const role of [ROLES.ADMIN, ROLES.RISK, ROLES.FINANCE]) expect(service).toContain(role)
    }
  })

  it('risk-engine writes: only ROLE_RISK joins the method-level @RolesAllowed, matching the form grant', () => {
    for (const source of [riskResource, curveResource]) {
      const write = rolesAllowed(source, 1)
      expect(write).toContain('ROLE_RISK')
      expect(write).not.toContain('ROLE_FINANCE')
    }
  })

  it('ledger backfill: the UI grants exactly LedgerBackfillResource’s roles, for every step', () => {
    expect(rolesAllowed(backfillResource)).toEqual(['ROLE_ADMIN', 'ROLE_FINANCE'])
    for (const p of ['ledger-backfill:view', 'ledger-backfill:propose', 'ledger-backfill:decide', 'ledger-backfill:execute'] as const) {
      const granted = Object.values(ROLES).filter(r => hasPermission([r], p)).sort()
      expect(granted, p).toEqual(['ROLE_ADMIN', 'ROLE_FINANCE'])
    }
  })
})

describe('IRRBB read — ADR-0313 phase 1', () => {
  it('the IRRBB endpoint is a plain read: risk.snapshot.read, no method-level role widening', () => {
    const at = riskResource.indexOf('@Path("/{id}/irrbb")')
    expect(at).toBeGreaterThan(0)
    const block = riskResource.slice(at, riskResource.indexOf('suspend fun irrbb', at))
    expect(block).toContain('@Authorize(action = "risk.snapshot.read", resource = "#id")')
    expect(block).not.toContain('@RolesAllowed')
  })

  it('the IRRBB page is visible to risk, finance and admin only', () => {
    const p = permissionForPath('/balance-sheet/snapshots/x/irrbb')!
    const granted = Object.values(ROLES).filter(r => hasPermission([r], p)).sort()
    expect(granted).toEqual([ROLES.ADMIN, ROLES.FINANCE, ROLES.RISK].sort())
  })
})

describe('LCR / NSFR read — ADR-0313 phase 1', () => {
  it('the liquidity endpoint is a plain read: risk.snapshot.read, no method-level role widening', () => {
    const at = riskResource.indexOf('@Path("/{id}/liquidity")')
    expect(at).toBeGreaterThan(0)
    const block = riskResource.slice(at, riskResource.indexOf('suspend fun liquidity', at))
    expect(block).toContain('@Authorize(action = "risk.snapshot.read", resource = "#id")')
    expect(block).not.toContain('@RolesAllowed')
  })

  it('the liquidity page is visible to risk, finance and admin only', () => {
    const p = permissionForPath('/balance-sheet/snapshots/x/liquidity')!
    const granted = Object.values(ROLES).filter(r => hasPermission([r], p)).sort()
    expect(granted).toEqual([ROLES.ADMIN, ROLES.FINANCE, ROLES.RISK].sort())
  })
})

describe('Credit-risk capital read — ADR-0313 phase 2', () => {
  it('the capital endpoint is a plain read: risk.snapshot.read, no method-level role widening', () => {
    const at = riskResource.indexOf('@Path("/{id}/capital")')
    expect(at).toBeGreaterThan(0)
    const block = riskResource.slice(at, riskResource.indexOf('suspend fun capital', at))
    expect(block).toContain('@Authorize(action = "risk.snapshot.read", resource = "#id")')
    expect(block).not.toContain('@RolesAllowed')
  })

  it('the capital page is visible to risk, finance and admin only', () => {
    const p = permissionForPath('/balance-sheet/snapshots/x/capital')!
    const granted = Object.values(ROLES).filter(r => hasPermission([r], p)).sort()
    expect(granted).toEqual([ROLES.ADMIN, ROLES.FINANCE, ROLES.RISK].sort())
  })
})
