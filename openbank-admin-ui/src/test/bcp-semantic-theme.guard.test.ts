// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

const source = readFileSync(path.resolve(__dirname, '../app/docs/bcp/page.tsx'), 'utf8')

describe('BCP semantic theme contract', () => {
  it('uses shared light/dark semantic colors without local palette literals', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    for (const token of [
      '--success', '--success-bg', '--success-border', '--success-text',
      '--warning', '--warning-bg', '--warning-border',
      '--danger', '--danger-bg', '--danger-border', '--danger-text',
      '--info', '--info-text', '--accent', '--surface-3', '--text-inverse',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('preserves live evidence and fail-closed compliance behavior', () => {
    expect(source).toContain("fetch('/api/services/health')")
    expect(source).toContain("fetch('/api/infra/status')")
    expect(source).toContain("const paymentsBlocked = complianceTierStatus.status !== 'healthy' && complianceTierStatus.status !== 'unknown'")
    expect(source).toContain('Compliance gate failed — payment processing BLOCKED')
    expect(source).toContain('5AMLD Art. 18')
    expect(source).toContain('DORA Art. 12')
  })
})
