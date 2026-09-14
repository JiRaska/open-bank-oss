// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

const source = readFileSync(
  path.resolve(__dirname, '../components/agent/AgentDiagnostics.module.css'),
  'utf8',
)

describe('agent diagnostics semantic theme contract', () => {
  it('derives diagnostic tones from the shared light and dark theme vocabulary', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    for (const token of [
      '--accent', '--accent-bg', '--accent-border', '--accent-text',
      '--chart-series-4', '--chart-series-5',
      '--success', '--success-bg', '--success-border', '--success-text',
      '--warning', '--warning-bg', '--warning-border', '--warning-text',
      '--danger', '--danger-bg', '--danger-text',
      '--info', '--info-bg', '--info-text',
      '--surface', '--surface-2', '--surface-3',
      '--text-primary', '--text-secondary', '--text-tertiary',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
    expect(source).toContain('color-mix(in srgb')
  })

  it('keeps planned, governed and human-required mesh states visually distinct', () => {
    expect(source).toContain('.meshNode[data-status="governed"]')
    expect(source).toContain('.meshNode[data-status="planned"]')
    expect(source).toContain('.meshNode[data-status="human"]')
    expect(source).toContain('border-style: dashed')
  })
})
