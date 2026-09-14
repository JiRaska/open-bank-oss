// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(path.resolve(__dirname, '../app/privacy/privacy.module.css'), 'utf8')
const globals = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')

describe('public privacy semantic theme', () => {
  it('keeps presentation in named privacy tokens', () => {
    expect(css).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    expect(css).not.toMatch(/rgba?\(/u)
    for (const token of ['bg', 'surface', 'text', 'heading', 'muted', 'accent', 'border', 'focus']) {
      expect(css).toContain(`var(--privacy-${token}`)
    }
  })

  it('provides a hydration-free operating-system dark palette', () => {
    expect(globals).toContain('@media (prefers-color-scheme: dark)')
    expect(globals).toContain('--privacy-bg: #07111f')
    expect(globals).toContain('--privacy-text: #e5edf7')
  })
})
