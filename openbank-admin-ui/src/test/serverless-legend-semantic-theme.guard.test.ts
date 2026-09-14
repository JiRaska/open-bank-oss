// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/finops/ServerlessLegend.tsx'), 'utf8')

describe('serverless legend semantic theme contract', () => {
  it('maps workload tiers to shared readable semantics', () => {
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    for (const token of ['--text-secondary', '--warning-text', '--success-text', '--info-text']) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('composes token-based tier borders as valid CSS', () => {
    expect(source).toContain('color-mix(in srgb, ${r.color} 33.3%, transparent)')
    expect(source).not.toMatch(/\$\{r\.color}55/)
  })
})
