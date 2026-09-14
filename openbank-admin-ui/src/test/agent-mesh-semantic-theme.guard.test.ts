// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/agent/AgentMeshExplainer.module.css'), 'utf8')

describe('agent mesh semantic theme contract', () => {
  it('uses shared status and surface semantics', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    for (const token of [
      '--surface', '--border', '--border-strong', '--text-primary', '--text-secondary', '--text-muted',
      '--success-text', '--warning-text', '--warning-bg', '--info', '--accent', '--shadow-lg',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('preserves the four-step responsive education flow', () => {
    expect(source).toContain('grid-template-columns: repeat(4')
    expect(source).toContain('.step:not(:last-child)::after')
    expect(source).toContain("content: '→'")
    expect(source).toContain("content: '↓'")
  })
})
