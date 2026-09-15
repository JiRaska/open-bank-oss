// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/agent/AgentDiagnostics.module.css'), 'utf8')

describe('agent diagnostics semantic theme contract', () => {
  it('contains no fixed light-theme colours or shadows', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
  })

  it('maps diagnostic meaning to shared semantic tokens', () => {
    for (const token of [
      '--accent-text', '--accent-bg', '--accent-border', '--info-text', '--info-bg',
      '--success-text', '--success-bg', '--success-border', '--warning-text', '--warning-bg',
      '--warning-border', '--danger-text', '--danger-bg', '--surface', '--surface-2', '--shadow-lg',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('keeps status and metric categories structurally labelled', () => {
    for (const tone of ['data', 'tools', 'guardrails', 'domain', 'tokens', 'cadence']) {
      expect(source).toContain(`[data-tone="${tone}"]`)
    }
    for (const status of ['governed', 'planned', 'human']) {
      expect(source).toContain(`[data-status="${status}"]`)
    }
  })

  it('keeps every diagnostic label at a readable 10px floor', () => {
    expect(source).not.toMatch(/font-size:\s*[7-9]px/)
    for (const selector of ['.anatomyTag', '.metricFoot', '.meshStatus', '.caseClasses', '.caseClasses code']) {
      expect(source).toMatch(new RegExp(`${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} \\{[^}]*font-size: 10px;`))
    }
  })
})
