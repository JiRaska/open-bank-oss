// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/layout/Sidebar.module.css'), 'utf8')

describe('sidebar semantic theme contract', () => {
  it('keeps the branded navigation palette in shared tokens', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    for (const token of [
      '--sidebar-canvas-glow', '--sidebar-canvas-top', '--sidebar-canvas-mid', '--sidebar-canvas-bottom',
      '--sidebar-brand-start', '--sidebar-brand-mid', '--sidebar-brand-end', '--sidebar-item',
      '--sidebar-item-hover', '--sidebar-item-active', '--sidebar-item-locked', '--sidebar-footer-version',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('preserves active, locked and mobile navigation states', () => {
    expect(source).toContain('.active:hover')
    expect(source).toContain('.locked:hover')
    expect(source).toContain('.mobileOpen')
    expect(source).toContain('var(--sidebar-shadow-mobile)')
  })
})
