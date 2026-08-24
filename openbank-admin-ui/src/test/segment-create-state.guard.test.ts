// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('segment draft creation state contract', () => {
  it('keeps audience draft submission truthful while saving', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/segments/new/page.tsx'), 'utf8')
    expect(source).toContain('type="submit" aria-busy={saving}')
    expect(source).toContain("aria-label={saving ? t('Ukládám návrh publika', 'Saving audience draft') : t('Vytvořit návrh publika', 'Create audience draft')}")
    expect(source).toContain('disabled={!validName || !validTenure || saving}')
    expect(source).toContain('<CheckCircle2 aria-hidden="true"')
  })
})
