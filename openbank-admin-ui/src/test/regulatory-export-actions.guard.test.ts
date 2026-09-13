// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('regulatory export actions contract', () => {
  it('keeps CSV and JSON export actions explicit', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/regulatory/page.tsx'), 'utf8')
    expect(source).toContain("aria-label={t('Exportovat náhled jako CSV', 'Export preview as CSV')}")
    expect(source).toContain("aria-label={t('Exportovat náhled jako JSON', 'Export preview as JSON')}")
    expect(source).toContain('onClick={() => exportCsv(preview)}')
    expect(source).toContain('onClick={() => exportJson(preview)}')
  })
})
