// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('Lending refresh contract', () => {
  it('exposes localized busy semantics without changing lending loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/lending/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit lending', 'Refresh lending')}")
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true"')
    expect(source).toContain('onClick={load}')
  })
})
