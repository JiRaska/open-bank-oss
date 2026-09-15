// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/segments/page.tsx'), 'utf8')
const createSource = readFileSync(path.resolve(__dirname, '../app/segments/new/page.tsx'), 'utf8')

describe('audience workspace semantic theme', () => {
  it('keeps content surfaces and lifecycle states adaptive without weakening maker-checker controls', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])|rgba?\(/iu)
    expect(source).not.toMatch(/\b(?:bg|text|border|hover:bg|hover:border)-(?:white|slate|violet|emerald|rose|amber)-/u)
    for (const token of ['surface', 'surface-2', 'text-primary', 'text-secondary', 'text-tertiary', 'accent-bg', 'accent-border', 'accent-text']) {
      expect(source).toContain(`var(--${token})`)
    }
    for (const tone of ['warning', 'danger', 'success']) {
      expect(source).toContain(`var(--${tone}-text)`)
    }
    expect(source).toContain("permission=\"campaign:activate\"")
    expect(source).toContain("action: 'submit' | 'approve'")
  })

  it('keeps the audience composer and its validation semantics adaptive', () => {
    expect(createSource).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])|rgba?\(/iu)
    expect(createSource).not.toMatch(/\b(?:bg|text|border|hover:bg|hover:border|focus:border|focus:ring)-(?:white|slate|violet|emerald|rose|amber)-/u)
    for (const token of ['surface', 'text-primary', 'text-secondary', 'text-tertiary', 'accent-bg', 'accent-border', 'success-bg', 'success-border', 'danger-bg', 'danger-border']) {
      expect(createSource).toContain(`var(--${token})`)
    }
    expect(createSource).toContain('permission="campaign:create"')
    expect(createSource).toContain("method: 'POST'")
  })
})
