// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/layout/Header.tsx'), 'utf8')

describe('global header semantic theme contract', () => {
  it('uses shared danger semantics for sign-out interaction states', () => {
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    expect(source).toContain("color: 'var(--danger-text)'")
    expect(source).toContain("style.background = 'var(--danger-bg)'")
  })

  it('composes role badge borders without appending hex alpha syntax', () => {
    expect(source).toContain('color-mix(in srgb, ${info.color} 13.3%, transparent)')
    expect(source).not.toMatch(/\$\{info\.color}22/)
  })
})
