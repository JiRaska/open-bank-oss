// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('agent tools loading contract', () => {
  it('announces loading and names refresh without changing MCP flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/system/agent/page.tsx'), 'utf8')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit nástroje agenta', 'Refresh agent tools')}")
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('onClick={loadTools}')
  })
})
