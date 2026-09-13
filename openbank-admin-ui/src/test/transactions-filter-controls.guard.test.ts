// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('transactions filter controls contract', () => {
  it('keeps disclosure and clear controls explicit', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/transactions/page.tsx'), 'utf8')
    expect(source).toContain('type="button" className="btn btn-secondary" onClick={() => setShowFilters(f => !f)}')
    expect(source).toContain('aria-controls="transaction-search-filters"')
    expect(source).toContain("aria-label={t('Vymazat filtry transakcí', 'Clear transaction filters')}")
    expect(source).toContain('onClick={clearFilters}')
  })
})
