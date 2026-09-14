// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/identity-cases/page.tsx'), 'utf8')

describe('identity-case semantic theme contract', () => {
  it('maps triggers and verdicts to complete shared tones', () => {
    // The page contains issue references, not CSS colours.
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    for (const token of [
      '--danger-text', '--danger-bg', '--danger-border',
      '--warning-text', '--warning-bg', '--warning-border',
      '--accent-text', '--accent-bg', '--accent-border', '--success-text', '--info-text',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
    expect(source).toContain('background: TRIGGER_TONE[c.trigger].background')
    expect(source).toContain('border: `1px solid ${TRIGGER_TONE[c.trigger].border}`')
  })

  it('keeps the maker-checker and conflict boundaries intact', () => {
    expect(source).toContain("c.status === 'AWAITING_SECOND_APPROVAL'")
    expect(source).toContain('you cannot vote twice')
    expect(source).toContain('useSingleFlight()')
    expect(source).toContain('res.status === 409')
  })
})
