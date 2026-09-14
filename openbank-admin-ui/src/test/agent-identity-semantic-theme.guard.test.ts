// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const identity = readFileSync(path.resolve(__dirname, '../components/agent/AgentIdentity.tsx'), 'utf8')
const portrait = readFileSync(path.resolve(__dirname, '../components/agent/AgentIdentity.module.css'), 'utf8')
const iaops = readFileSync(path.resolve(__dirname, '../app/iaops/page.tsx'), 'utf8')
const profile = readFileSync(path.resolve(__dirname, '../app/iaops/agents/[agentId]/page.tsx'), 'utf8')

describe('agent identity semantic theme contract', () => {
  it('derives every persona and robot surface from the shared light/dark palette', () => {
    expect(identity).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    expect(portrait).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    for (const token of [
      '--accent-text', '--accent-border', '--accent-bg',
      '--success-text', '--success-border', '--success-bg',
      '--warning-text', '--warning-border', '--warning-bg',
      '--danger-text', '--danger-border', '--danger-bg',
      '--info-text', '--info-border', '--info-bg',
      '--text-primary', '--text-secondary', '--text-tertiary',
      '--surface', '--surface-2', '--surface-4', '--border-strong',
    ]) {
      expect(`${identity}\n${portrait}`).toContain(`var(${token})`)
    }
  })

  it('uses color-mix instead of invalid alpha suffixes on semantic variables', () => {
    expect(`${iaops}\n${profile}`).not.toMatch(/persona\.(?:accent|glow|shell)}[0-9a-f]{2}/i)
    expect(iaops).toContain('color-mix(in srgb, ${persona.accent}')
    expect(profile).toContain('color-mix(in srgb, ${persona.accent}')
  })
})
