// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('day-end refresh contract', () => {
  it('keeps both refresh controls accessible and closing actions intact', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/day-end/page.tsx'), 'utf8')
    expect(source).toContain("aria-label={t('Obnovit denní závěrku', 'Refresh day-end close')}")
    expect(source).toContain("aria-label={t('Obnovit měsíční závěrku', 'Refresh month-end close')}")
    expect(source).toContain('onClick={() => refresh(true)}')
    expect(source).toContain('onClick={() => load(true)}')
    expect(source).toContain('<Clock size={12} aria-hidden="true"')
  })
})
