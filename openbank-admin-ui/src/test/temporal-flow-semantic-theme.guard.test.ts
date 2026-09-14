// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/temporal/flow/page.tsx'), 'utf8')

describe('Temporal flow semantic theme contract', () => {
  it('uses shared semantics for controls and saga compensation', () => {
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    for (const token of ['--accent', '--accent-strong', '--danger', '--on-accent', '--surface', '--text-primary']) {
      expect(source).toContain(`var(${token})`)
    }
    expect(source).toContain('color="var(--danger)"')
  })

  it('composes workflow tints and preserves evidence wording', () => {
    expect(source).toContain('color-mix(in srgb, ${w.color} 10%, transparent)')
    expect(source).not.toMatch(/\$\{w\.color}1a/)
    expect(source).toContain('Reference diagrams below describe code, not current executions.')
    expect(source).toContain('Compensation (only on failure)')
  })
})
