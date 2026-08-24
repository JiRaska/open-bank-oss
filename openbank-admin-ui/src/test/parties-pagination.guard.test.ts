// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('parties pagination contract', () => {
  it('keeps cursor pagination explicit and accessible', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/parties/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain("aria-label={t('Načíst další subjekty', 'Load more parties')}")
    expect(source).toContain('<ChevronDown size={13} aria-hidden="true" />')
    expect(source).toContain('runSearch(debouncedQ, searchPagi.nextCursor)')
  })
})
