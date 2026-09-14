// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/infra/LifecycleStrip.tsx'), 'utf8')

describe('infrastructure lifecycle semantic theme contract', () => {
  it('uses shared status semantics without local presentation colours', () => {
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    for (const token of [
      '--success-bg', '--success-border', '--success-text', '--warning-bg', '--warning-text',
      '--danger-bg', '--danger-text', '--info-bg', '--info-text', '--text-secondary',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('composes runtime badge colours as valid CSS', () => {
    expect(source).toContain('color-mix(in srgb, ${u.color} 20%, transparent)')
    expect(source).not.toMatch(/\$\{u\.color}33/)
  })
})
