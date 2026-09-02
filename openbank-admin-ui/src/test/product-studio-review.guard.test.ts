// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('product studio review control contract', () => {
  it('keeps the human-in-the-loop review action explicit', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/product-studio/page.tsx'), 'utf8')
    expect(source).toContain('type="button" aria-busy={reviewing}')
    expect(source).toContain("aria-label={reviewing ? t('Kontroluji draft', 'Reviewing draft') : t('Spustit AI kontrolu draftu', 'Run AI review for draft')}")
    expect(source).toContain('disabled={!selectedRevision || selectedRevision.state !== \'DRAFT\' || reviewing')
    expect(source).toContain('onClick={() => void reviewDraft()}')
  })
})
