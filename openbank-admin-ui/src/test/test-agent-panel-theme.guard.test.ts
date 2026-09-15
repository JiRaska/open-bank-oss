// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/testing/TestAgentPanel.tsx'), 'utf8')

describe('test agent governance theme contract', () => {
  it('uses the shared accessible evidence tones instead of fixed light-theme colors', () => {
    expect(source).not.toContain('#16a34a')
    expect(source).not.toContain('#d97706')
    expect(source).toContain("governance.evalEvidence === 'recorded' ? 'var(--success-text)' : 'var(--warning-text)'")
  })
})
