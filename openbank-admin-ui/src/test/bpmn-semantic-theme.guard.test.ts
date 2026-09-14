// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

const source = readFileSync(path.resolve(__dirname, '../components/docs/BpmnView.tsx'), 'utf8')

describe('BPMN semantic theme contract', () => {
  it('derives lanes, nodes and events from the shared theme vocabulary', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])|rgba?\(/i)
    for (const token of [
      '--surface-2', '--border', '--text-primary', '--text-tertiary', '--on-accent',
      '--accent-bg', '--accent-border',
      '--success', '--success-bg', '--success-border', '--success-text',
      '--warning', '--warning-bg', '--warning-border', '--warning-text',
      '--danger', '--danger-bg', '--danger-border', '--danger-text',
      '--info-bg', '--info-border', '--info-text',
      '--map-core', '--map-edge-async-active',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('preserves the distinct BPMN event and transport vocabulary', () => {
    expect(source).toContain("type === 'start'")
    expect(source).toContain("type === 'end'")
    expect(source).toContain("type === 'end-err'")
    expect(source).toContain("type === 'gateway'")
    expect(source).toContain("type === 'event'")
    expect(source).toContain("f.kind === 'async'")
    expect(source).toContain('strokeDasharray={isAsync')
  })
})
