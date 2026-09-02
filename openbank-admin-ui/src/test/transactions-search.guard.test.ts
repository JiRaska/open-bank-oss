// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('transactions search contract', () => {
  it('exposes localized busy semantics without changing search flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/transactions/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("t('Vyhledávání transakcí', 'Searching transactions')")
    expect(source).toContain("t('Vyhledat transakce', 'Search transactions')")
    expect(source).toContain('onClick={() => search()}')
  })
})
