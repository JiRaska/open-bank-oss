// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('account detail loading contract', () => {
  it('announces loading without changing account lifecycle flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/accounts/[id]/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true"')
    expect(source).toContain("t('Načítám účet…', 'Loading account…')")
  })
})
