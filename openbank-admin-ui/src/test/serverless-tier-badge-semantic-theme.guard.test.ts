// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/finops/ServerlessTierBadge.tsx'), 'utf8')

describe('serverless tier badge semantic theme contract', () => {
  it('maps live, planned and neutral states to shared readable tokens', () => {
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    for (const token of [
      '--success-text', '--success-bg', '--success-border',
      '--warning-text', '--warning-bg', '--warning-border',
      '--text-secondary', '--surface-2', '--border',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('does not reduce tier-label contrast with opacity', () => {
    expect(source).not.toMatch(/opacity:\s*0\.6/)
  })
})
