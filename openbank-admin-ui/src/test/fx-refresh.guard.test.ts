// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('FX refresh contract', () => {
  it('keeps rate refresh controls explicit and localized', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/fx/page.tsx'), 'utf8')
    expect(source).toContain("aria-label={t('Obnovit kurzy FX', 'Refresh FX rates')}")
    expect(source).toContain("aria-label={t('Stáhnout všechny kurzy FX', 'Fetch all FX rates')}")
    expect(source).toContain('aria-busy={refreshing === \'all\'}')
    expect(source).toContain('manualRefresh(\'all\')')
  })
})
