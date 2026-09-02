// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('standing orders loading contract', () => {
  it('announces the existing loading state without changing data flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/standing-orders/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={20} aria-hidden="true"')
    expect(source).toContain("t('Načítám…', 'Loading…')")
  })
})
