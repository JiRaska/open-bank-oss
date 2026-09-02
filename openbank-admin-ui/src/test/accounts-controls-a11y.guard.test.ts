// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('accounts controls accessibility contract', () => {
  it('keeps search, reset and pagination controls explicit', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/accounts/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain("aria-label={t('Vyhledat účty', 'Search accounts')}")
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Vymazat filtry účtů', 'Reset account filters')}")
    expect(source).toContain("aria-label={t('Zobrazit další účty', 'Load more accounts')}")
    expect(source).toContain('onClick={() => setVisibleCount(c => c + PAGE_SIZE)}')
  })
})
