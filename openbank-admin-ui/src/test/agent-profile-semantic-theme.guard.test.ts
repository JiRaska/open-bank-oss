// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const profile = readFileSync(path.resolve(__dirname, '../app/iaops/agents/[agentId]/page.tsx'), 'utf8')
const outcomes = readFileSync(path.resolve(__dirname, '../components/agent/AgentOutcomes.tsx'), 'utf8')

describe('agent profile semantic theme contract', () => {
  it('keeps profile and outcome states free of fixed colours', () => {
    for (const source of [profile, outcomes]) {
      expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    }
  })

  it('uses shared approval, denial and review semantics', () => {
    for (const token of [
      '--accent-text', '--success-text', '--success-bg', '--warning-text', '--warning-bg',
      '--danger-text', '--danger-bg',
    ]) {
      expect(profile).toContain(`var(${token})`)
    }
    expect(outcomes).toContain('var(--warning-text)')
    expect(profile).toContain("textDecoration: 'underline'")
  })
})
