// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

const source = readFileSync(path.resolve(__dirname, '../app/privacy/privacy.module.css'), 'utf8')

describe('privacy semantic theme contract', () => {
  it('uses the shared theme without a page-local colour system', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    for (const token of [
      '--bg', '--surface', '--border', '--border-strong',
      '--text-primary', '--text-secondary', '--text-tertiary',
      '--success', '--success-bg', '--success-border', '--success-text',
      '--info', '--link', '--on-accent', '--sidebar-bg',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
    expect(source).toContain('color-mix(in srgb')
  })

  it('keeps the public security contact visually distinct from content surfaces', () => {
    expect(source).toContain('linear-gradient(140deg, var(--sidebar-bg)')
    expect(source).toContain('.contactLinks a')
    expect(source).toContain('var(--on-accent)')
  })
})
