// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('disputes loading contract', () => {
  it('announces the existing loading state without changing dispute data flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/disputes/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={20} aria-hidden="true"')
    expect(source).toContain("t('Načítám…', 'Loading…')")
  })
})
