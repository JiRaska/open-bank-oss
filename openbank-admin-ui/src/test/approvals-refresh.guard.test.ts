// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('Approval queue refresh contract', () => {
  it('exposes a localized refresh name without changing approval loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/approvals/page.tsx'), 'utf8')
    expect(source).toContain("aria-label={t('Obnovit schvalovací frontu', 'Refresh approval queue')}")
    expect(source).toContain('onClick={load}')
    expect(source).toContain('aria-busy={loading}')
  })
})
