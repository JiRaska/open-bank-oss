// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('IAOPS refresh contract', () => {
  it('exposes localized busy semantics without changing IAOPS loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/iaops/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit IAOPS', 'Refresh IAOPS')}")
    expect(source).toContain('onClick={load}')
  })
})
