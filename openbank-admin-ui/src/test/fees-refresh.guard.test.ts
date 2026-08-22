// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('fees refresh contract', () => {
  it('exposes localized busy semantics without changing fee loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/fees/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit ceník poplatků', 'Refresh fee schedule')}")
    expect(source).toContain('onClick={() => void load()}')
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true"')
  })
})
