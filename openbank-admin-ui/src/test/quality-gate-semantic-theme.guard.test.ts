// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/devops/QualityGateHealthPanel.tsx'), 'utf8')

describe('quality-gate health semantic theme', () => {
  it('renders gate failures through shared adaptive danger semantics', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    expect(source).toContain('var(--danger-bg)')
    expect(source).toContain('var(--danger-border)')
    expect(source).toContain('var(--danger-text)')
    expect(source).toContain('var(--warning-text)')
  })
})
