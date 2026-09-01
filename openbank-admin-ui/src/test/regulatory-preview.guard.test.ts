// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('regulatory preview loading contract', () => {
  it('exposes localized busy semantics without changing preview flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/regulatory/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain("aria-busy={previewData.status === 'loading'}")
    expect(source).toContain("aria-label={t('Načíst data pro náhled', 'Load preview data')}")
    expect(source).toContain(
      'onClick={() => void loadPreview(preview, reportingDate || undefined, reportingEvidence)}',
    )
  })
})
