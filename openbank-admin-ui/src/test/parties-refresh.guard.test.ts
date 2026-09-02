// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('Parties refresh contract', () => {
  it('exposes localized busy semantics without changing party loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/parties/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading || inSearchMode}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit subjekty', 'Refresh parties')}")
    expect(source).toContain('onClick={load}')
  })
})
