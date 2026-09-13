import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/interest/page.tsx'), 'utf8')

describe('interest calculation presentation', () => {
  it('uses the active locale for monetary values and names the search field', () => {
    expect(page).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(page).toContain('toLocaleString(numberLocale')
    expect(page).not.toContain("toLocaleString('cs-CZ'")
    expect(page).toContain("aria-label={t('Hledat v úrokových výpočtech', 'Search interest calculations')}")
  })

  it('keeps presentation icons out of the accessibility tree', () => {
    expect(page).toContain('<Search size={13} aria-hidden="true"')
    expect(page).toContain('<TrendingUp size={16} aria-hidden="true"')
  })
})
