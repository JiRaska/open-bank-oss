// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'fs'
import { describe, expect, it } from 'vitest'

describe('Test Intelligence discoverability', () => {
  it('is a primary platform destination and a platform workspace shortcut', () => {
    const sidebar = readFileSync('src/components/layout/Sidebar.tsx', 'utf8')
    const persona = readFileSync('src/lib/auth/persona.ts', 'utf8')
    const platform = sidebar.slice(sidebar.indexOf('const platformNav'), sidebar.indexOf('const toolsNav'))

    expect(platform.indexOf("href: '/system/tests'")).toBeGreaterThan(-1)
    expect(platform.indexOf("href: '/system/tests'")).toBeLessThan(platform.indexOf("href: '/finops'"))
    expect(sidebar.match(/href: '\/system\/tests'/g)).toHaveLength(1)
    expect(persona).toContain("href: '/system/tests', nameCs: 'Test Intelligence'")
  })
})
