// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// /api/platform-versions and /api/finops/* disclose infra versions and node counts. The proxy only
// authenticates them; permissionForPath must map them to the same permission as the pages that
// render them, or ANY signed-in role (a bare ROLE_VIEWER) can read them.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { hasPermission, permissionForPath } from '@/lib/auth/roles'

const PATHS = ['/api/platform-versions', '/api/finops/lifecycle', '/api/finops/resources']

describe('infra version APIs require system:view', () => {
  it('maps to system:view', () => {
    for (const p of PATHS) expect(permissionForPath(p)).toBe('system:view')
  })

  it('denies ROLE_VIEWER and allows the roles that hold system:view', () => {
    for (const p of PATHS) {
      const permission = permissionForPath(p)!
      expect(hasPermission(['ROLE_VIEWER'], permission)).toBe(false)
      expect(hasPermission(['ROLE_OPERATOR'], permission)).toBe(true)
      expect(hasPermission(['ROLE_ADMIN'], permission)).toBe(true)
    }
  })

  it('the proxy answers API paths with JSON 403, not an HTML redirect', () => {
    const proxy = readFileSync('src/proxy.ts', 'utf8')
    expect(proxy).toMatch(/startsWith\(["']\/api\/["']\)[\s\S]{0,200}status: 403/)
  })

  it('the routes never forward raw upstream/Prometheus error text', () => {
    for (const f of ['src/app/api/platform-versions/route.ts', 'src/app/api/finops/lifecycle/route.ts']) {
      const src = readFileSync(f, 'utf8')
      expect(src).not.toMatch(/liveError|\.message|String\(e/)
    }
  })
})
