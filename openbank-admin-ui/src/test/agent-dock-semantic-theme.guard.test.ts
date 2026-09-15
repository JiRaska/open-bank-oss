// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/agent/AgentDock.tsx'), 'utf8')
const launcher = readFileSync(path.resolve(__dirname, '../components/agent/LazyAgentDock.tsx'), 'utf8')

describe('agent dock semantic theme contract', () => {
  it('uses mandatory shared tokens for messages, proposals and floating layers', () => {
    const completeDock = `${launcher}\n${source}`
    expect(completeDock).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    expect(completeDock).not.toMatch(/rgba?\(/i)
    for (const token of [
      '--accent-strong', '--text-inverse', '--warning-text', '--warning-bg', '--warning-border',
      '--floating-action-shadow', '--floating-panel-shadow', '--success', '--danger',
    ]) {
      expect(completeDock).toContain(`var(${token})`)
    }
  })

  it('keeps proposal review and policy evidence visible', () => {
    expect(source).toContain('m.isProposal')
    expect(source).toContain('Requires your review before acting')
    expect(source).toContain('policy-gated · audited')
    expect(source).toContain('tc.allowed')
  })
})
