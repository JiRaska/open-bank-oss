// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ROLES, hasPermission, permissionForPath, type Permission } from '@/lib/auth/roles'

/**
 * The UI half of the `payments:view` bucket fix (#7790, #7783, #7788, #7824).
 *
 * The Kotlin half — "does this persona set match the backing service's @RolesAllowed" — is
 * NOT tested here and cannot usefully be: parsing Kotlin annotations from vitest would put a
 * second, weaker parser in a second language next to the real one. That comparison lives in
 * `.github/scripts/check-admin-ui-rbac-alignment.py`, which reads both sides and is falsified
 * against a widened permission and a narrowed backend in its own `--self-test`.
 *
 * What IS tested here is everything downstream of that pin, which the Python gate cannot see:
 * that the nav and the route projection agree, and that the specific personas the twelve
 * backends reject can no longer reach the twelve pages.
 */

const root = resolve(__dirname, '..')
const read = (file: string) => readFileSync(resolve(root, file), 'utf8')

const BUCKET_ROUTES = [
  '/payments', '/product-catalog', '/standing-orders', '/sdd', '/sepa-instant', '/clearing',
  '/fx', '/swift', '/interest', '/pid', '/fees', '/lending',
] as const

describe('the twelve-route payments:view bucket is split per backing service', () => {
  it('no longer maps every payment-adjacent route to one permission', () => {
    const permissions = BUCKET_ROUTES.map(r => permissionForPath(r))
    expect(permissions.every(Boolean)).toBe(true)
    // Before the fix this set had exactly one member. Ten services back these twelve routes.
    expect(new Set(permissions).size).toBe(10)
  })

  it('keeps only same-service routes sharing a permission', () => {
    // /sepa-instant redirects to /payments and reads the same three resources.
    expect(permissionForPath('/sepa-instant')).toBe(permissionForPath('/payments'))
    // /fees is served by product-catalog.
    expect(permissionForPath('/fees')).toBe(permissionForPath('/product-catalog'))
    // Everything else is its own service, so its own permission.
    for (const [a, b] of [['/interest', '/payments'], ['/pid', '/payments'],
                          ['/standing-orders', '/payments'], ['/lending', '/payments'],
                          ['/swift', '/clearing'], ['/fx', '/sdd']] as const) {
      expect(permissionForPath(a)).not.toBe(permissionForPath(b))
    }
  })

  it('keeps the stricter lending compliance prefix winning over /lending', () => {
    expect(permissionForPath('/lending')).toBe('lending:view')
    expect(permissionForPath('/lending/compliance-packs')).toBe('lending:compliance:view')
  })
})

describe('personas the backends reject can no longer reach the pages', () => {
  // ROLE_SUPERVISOR was granted on all twelve routes and is admitted by none of them.
  it('hides every one of the twelve from a supervisor', () => {
    for (const route of BUCKET_ROUTES) {
      const permission = permissionForPath(route) as Permission
      expect(hasPermission([ROLES.SUPERVISOR], permission),
        `supervisor must not be shown ${route}`).toBe(false)
    }
  })

  // StandingOrderResource admits no ROLE_VIEWER on any method; pid-service's read path and
  // LendingResource's applications/* reads are OPERATOR/ADMIN-tier.
  it.each(['/standing-orders', '/pid', '/lending'])('hides %s from a plain viewer', route => {
    expect(hasPermission([ROLES.VIEWER], permissionForPath(route) as Permission)).toBe(false)
  })

  // ROLE_PAYMENTS is not admitted by product-catalog, standing-order, interest, pid or lending.
  it.each(['/product-catalog', '/fees', '/standing-orders', '/interest', '/pid', '/lending'])(
    'hides %s from payments ops', route => {
      expect(hasPermission([ROLES.PAYMENTS], permissionForPath(route) as Permission)).toBe(false)
    })

  // The controls: the personas the backends DO admit must keep their access, or this whole
  // change is an outage rather than a correction.
  it('keeps the personas each backend admits', () => {
    expect(hasPermission([ROLES.PAYMENTS], 'payments:view')).toBe(true)
    expect(hasPermission([ROLES.VIEWER], 'fx:view')).toBe(true)
    expect(hasPermission([ROLES.VIEWER], 'swift:view')).toBe(true)
    expect(hasPermission([ROLES.VIEWER], 'sdd:view')).toBe(true)
    expect(hasPermission([ROLES.VIEWER], 'interest:view')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'standing-orders:view')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'pid:view')).toBe(true)
    expect(hasPermission([ROLES.CREDIT_RISK], 'lending:view')).toBe(true)
    expect(hasPermission([ROLES.LENDING_OFFICER], 'lending:view')).toBe(true)
    expect(hasPermission([ROLES.CATALOG_READ], 'product-catalog:view')).toBe(true)
    for (const route of BUCKET_ROUTES) {
      expect(hasPermission([ROLES.ADMIN], permissionForPath(route) as Permission)).toBe(true)
    }
  })
})

describe('nav destinations agree with the route projection', () => {
  // DERIVED from the nav sources, not a hand-kept list: a nav entry added later is covered
  // automatically. This is what stops a link being gated by one permission while the route it
  // points at is gated by another — the shape that let /pid sit behind payments:view.
  const entries = (file: string) =>
    [...read(file).matchAll(/href: '(\/[\w/-]+)'[^}]*?permission: '([\w:-]+)'/g)]
      .map(m => ({ href: m[1], permission: m[2] }))

  // RATCHET, not an allowlist: the mismatch set is asserted EXACTLY, so a new one fails and a
  // fixed one must be removed from here. The single declared entry is PRE-EXISTING and outside
  // this change — persona.ts offers /sanctions to the compliance and supervisor workspaces under
  // `compliance:view`, while the route resolves to `sanctions:view` (ADMIN/OPERATOR/VIEWER), so
  // the edge gate refuses the link the workspace advertises. It is the same defect class as the
  // payments bucket and is left alone deliberately: `sanctions:view` has not been verified
  // against sanctions-service's @RolesAllowed, and guessing which of the two sides is wrong is
  // how the bucket got its extra personas in the first place. Tracked for follow-up.
  const DECLARED_NAV_MISMATCHES: Record<string, Array<{ href: string; permission: string }>> = {
    'components/layout/Sidebar.tsx': [],
    'lib/auth/persona.ts': [
      { href: '/sanctions', permission: 'compliance:view' },
      { href: '/sanctions', permission: 'compliance:view' },
    ],
  }

  it.each(['components/layout/Sidebar.tsx', 'lib/auth/persona.ts'])(
    '%s gates each link with the permission its route resolves to', file => {
      const mismatched = entries(file)
        .map(e => ({ ...e, route: permissionForPath(e.href) }))
        .filter(e => e.route !== undefined && e.route !== e.permission)
        .map(({ href, permission }) => ({ href, permission }))
      expect(mismatched).toEqual(DECLARED_NAV_MISMATCHES[file])
    })

  it('reads a non-empty set of nav entries from both files', () => {
    // Known-positive: the regex above returning nothing would make the assertion vacuous.
    expect(entries('components/layout/Sidebar.tsx').length).toBeGreaterThan(20)
    expect(entries('lib/auth/persona.ts').length).toBeGreaterThan(10)
  })
})
