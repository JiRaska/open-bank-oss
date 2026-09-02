// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('FinOps allocation loading contract', () => {
  it('announces cost allocation loading without changing the BFF flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/finops/allocation/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={16} aria-hidden="true"')
    expect(source).toContain("t('Počítám rozpad nákladů…', 'Computing cost allocation…')")
    expect(source).toContain("fetch('/api/finops/allocation'")
  })
})
