// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/infra/LifecycleStrip.tsx'), 'utf8')

describe('infrastructure lifecycle theme semantics', () => {
  it('uses adaptive status tokens for every lifecycle and proposal state', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    for (const token of [
      'success-text', 'success-bg', 'success-border',
      'warning-text', 'warning-bg', 'danger-text', 'danger-bg',
      'info-text', 'info-bg',
    ]) {
      expect(source).toContain(`var(--${token})`)
    }
  })
})
