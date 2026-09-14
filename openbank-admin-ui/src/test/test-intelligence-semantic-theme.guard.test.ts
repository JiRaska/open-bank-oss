// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const flow = readFileSync(path.resolve(__dirname, '../components/testing/TestIntelligenceFlow.tsx'), 'utf8')
const tokens = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')

describe('Test Intelligence semantic theme contract', () => {
  it('keeps the evidence canvas palette in the shared token stylesheet', () => {
    expect(flow).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)

    for (const token of [
      '--ti-canvas-start', '--ti-canvas-middle', '--ti-canvas-end',
      '--ti-ink', '--ti-copy', '--ti-copy-muted', '--ti-grid',
      '--ti-stage-change', '--ti-stage-prove', '--ti-stage-runtime',
      '--ti-stage-challenge', '--ti-stage-observe', '--ti-stage-reason',
      '--ti-stage-decide', '--ti-state-passed', '--ti-state-failed',
      '--ti-state-unresolved',
    ]) {
      expect(tokens).toContain(`${token}:`)
      expect(flow).toContain(`var(${token})`)
    }
  })
})
