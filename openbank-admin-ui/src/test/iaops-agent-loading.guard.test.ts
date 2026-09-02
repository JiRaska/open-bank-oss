// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('IAOPS agent loading contract', () => {
  it('announces agent detail loading without changing probe flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/iaops/agents/[agentId]/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={16} aria-hidden="true"')
    expect(source).toContain("t('Načítám agenta…', 'Loading agent…')")
    expect(source).toContain('triggerBoundedCheck')
  })
})
