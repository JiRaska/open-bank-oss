// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/cards/CardCapabilityMatrix.tsx'), 'utf8')

describe('card capability matrix semantic theme', () => {
  it('keeps build truth, network availability and bindings adaptive', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])|rgba?\(/iu)
    expect(source).not.toMatch(/\b(?:bg|text|border|divide|hover:bg|hover:text|hover:border)-(?:white|slate|violet|emerald|rose|amber)-/u)
    for (const token of [
      'surface', 'surface-2', 'surface-3', 'border',
      'text-primary', 'text-secondary', 'text-tertiary',
      'success-bg', 'success-border', 'success-text',
      'warning-bg', 'warning-border', 'warning-text',
    ]) {
      expect(source).toContain(`var(--${token})`)
    }
    expect(source).toContain("sandbox: 'bg-[var(--success-bg)]")
    expect(source).toContain("contract: 'bg-[var(--warning-bg)]")
    expect(source).toContain("none: 'bg-[var(--surface-3)]")
    expect(source).toContain('<TableViewport')
  })
})
