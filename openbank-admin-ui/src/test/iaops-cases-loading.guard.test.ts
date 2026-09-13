// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('IAOPS cases loading contract', () => {
  it('announces loading and keeps pagination controls explicit', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/iaops/cases/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={16} aria-hidden="true"')
    expect(source).toContain("t('Načítám case…', 'Loading cases…')")
    expect(source).toContain('type="button"')
  })
})
