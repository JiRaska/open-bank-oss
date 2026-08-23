// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('lending overview controls contract', () => {
  it('keeps tabs and stage filter explicit without changing data flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/lending/page.tsx'), 'utf8')
    expect(source).toContain('aria-pressed={tab === id}')
    expect(source).toContain("t('Zobrazit frontu žádostí', 'Show applications queue')")
    expect(source).toContain("t('Zobrazit portfolio', 'Show portfolio')")
    expect(source).toContain("aria-label={t('Zrušit filtr fáze', 'Clear stage filter')}")
    expect(source).toContain('setTab(id)')
  })
})
