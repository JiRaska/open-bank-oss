// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/auth/recovery.module.css'), 'utf8')

describe('authentication recovery semantic theme contract', () => {
  it('uses the shared colour system for both recovery boundaries', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    for (const token of [
      '--bg', '--surface', '--surface-2', '--border', '--border-strong',
      '--text-primary', '--text-secondary', '--text-tertiary',
      '--warning-bg', '--warning-border', '--warning-text',
      '--info-bg', '--info-border', '--info-text',
      '--success-bg', '--success-border', '--success-text',
      '--auth-action-start', '--auth-action-end', '--auth-story-text',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })
})
