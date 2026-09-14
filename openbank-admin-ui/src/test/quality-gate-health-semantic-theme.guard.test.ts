// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/devops/QualityGateHealthPanel.tsx'), 'utf8')

describe('quality-gate health semantic theme contract', () => {
  it('uses shared status semantics for every gate-health state', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    for (const token of [
      '--danger-bg', '--danger-border', '--danger-text', '--warning-text',
      '--surface-2', '--border', '--text-primary', '--text-secondary', '--text-tertiary',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('keeps successful and failed shards visually distinct', () => {
    expect(source).toContain("s.conclusion === 'success'")
    expect(source).toContain("tone === 'bad'")
    expect(source).toContain("tone === 'warn'")
  })
})
