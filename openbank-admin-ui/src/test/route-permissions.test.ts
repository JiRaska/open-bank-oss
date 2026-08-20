// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { permissionForPath } from '@/lib/auth/roles'

const APP_ROOT = 'src/app'
const PUBLIC_ROUTES = new Set(['/', '/auth/error', '/auth/forbidden', '/auth/login', '/privacy'])

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

  it('uses the most specific matching prefix', () => {
    expect(permissionForPath('/dashboard')).toBe('dashboard:view')
    expect(permissionForPath('/accounts/new')).toBe('accounts:create')
    expect(permissionForPath('/cards/sample')).toBe('cards:view')
    expect(permissionForPath('/lending/compliance-packs')).toBe('lending:compliance:view')
    expect(permissionForPath('/system/config')).toBe('system:config')
  })
})
