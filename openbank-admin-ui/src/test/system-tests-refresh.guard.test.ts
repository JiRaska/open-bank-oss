// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('System tests refresh contract', () => {
  it('exposes a localized refresh name without changing test loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/system/tests/page.tsx'), 'utf8')
    expect(source).toContain("aria-label={t('Obnovit systémové testy', 'Refresh system tests')}")
    expect(source).toContain('onClick={load}')
    expect(source).toContain('aria-busy={testLoading || qualityLoading}')
  })
})
