// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/onboarding/page.tsx'), 'utf8')

describe('onboarding stage theme semantics', () => {
  it('uses complete adaptive tone triples instead of invalid opacity suffixes', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    expect(source).not.toContain('STAGE_COLOR')
    for (const tone of ['warning', 'accent', 'info', 'success', 'danger']) {
      expect(source).toContain(`color: 'var(--${tone}-text)'`)
      expect(source).toContain(`background: 'var(--${tone}-bg)'`)
      expect(source).toContain(`border: 'var(--${tone}-border)'`)
    }
    expect(source).not.toMatch(/\$\{[^}]+\}(?:18|22)/u)
    expect(source).toContain("color: isActive ? tone.color : 'var(--text-secondary)'")
    expect(source).toContain("color: 'var(--accent-text)', display: 'flex'")
  })
})
