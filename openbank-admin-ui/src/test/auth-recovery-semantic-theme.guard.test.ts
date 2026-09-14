// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(path.resolve(__dirname, '../app/auth/recovery.module.css'), 'utf8')
const globals = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')

describe('pre-auth recovery semantic theme', () => {
  it('uses named adaptive tokens instead of raw colours', () => {
    expect(css).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    expect(css).not.toMatch(/rgba?\(/u)
    for (const token of ['bg', 'surface', 'text', 'heading', 'muted', 'border', 'focus']) {
      expect(css).toContain(`var(--privacy-${token}`)
    }
    for (const token of ['warning-bg', 'warning-border', 'warning-text', 'info-bg', 'info-border', 'info-text', 'card-shadow']) {
      expect(css).toContain(`var(--recovery-${token})`)
    }
  })

  it('defines light and hydration-free dark recovery palettes', () => {
    expect(globals).toContain('--recovery-warning-bg: #fff7ed')
    expect(globals).toContain('@media (prefers-color-scheme: dark)')
    expect(globals).toContain('--recovery-warning-bg: #3b220c')
    expect(globals).toContain('--recovery-info-bg: #102a44')
  })
})
