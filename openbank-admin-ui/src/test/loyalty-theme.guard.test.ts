// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const root = path.resolve(__dirname, '..')
const moduleCss = readFileSync(path.join(root, 'app/loyalty/loyalty.module.css'), 'utf8')
const page = readFileSync(path.join(root, 'app/loyalty/page.tsx'), 'utf8')
const globals = readFileSync(path.join(root, 'app/globals.css'), 'utf8')

describe('Lípa workspace theme identity', () => {
  it('keeps programme colours in named tokens and component surfaces adaptive', () => {
    expect(moduleCss).not.toMatch(/#[0-9a-f]{3,8}\b/iu)
    for (const token of [
      'hero-start', 'hero-end', 'action', 'on-brand', 'brand-accent',
      'brand-muted', 'brand-soft', 'brand-border', 'brand-wash', 'card-shadow',
    ]) {
      expect(moduleCss).toContain(`var(--loyalty-${token})`)
      expect(globals).toContain(`--loyalty-${token}:`)
    }
    expect(moduleCss).toContain('background: var(--surface)')
    expect(moduleCss).toContain('color: var(--text-primary)')
  })

  it('keeps every JSX content surface on shared semantic tokens', () => {
    expect(page).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/iu)
    expect(page).not.toMatch(/\b(?:bg|text|border|hover:bg|hover:text|hover:border|focus:ring|focus:border)-(?:white|slate|violet|emerald|rose|amber|blue|indigo|green|red|yellow|orange|teal|cyan)-/u)
    for (const token of [
      'surface-2', 'border', 'border-strong',
      'text-primary', 'text-secondary', 'text-tertiary', 'text-inverse',
      'accent-strong', 'accent-hover', 'accent-text',
      'success-text', 'warning-text', 'danger-text',
    ]) {
      expect(page).toContain(`var(--${token})`)
    }
    expect(page).toContain('permission="loyalty:view"')
    expect(page).toContain("fetch('/api/loyalty'")
  })
})
