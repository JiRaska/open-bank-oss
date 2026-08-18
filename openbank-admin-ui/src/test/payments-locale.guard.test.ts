import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('payments locale formatting', () => {
  it('uses the active language for money and timestamps', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/app/payments/page.tsx'), 'utf8')
    expect(source).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(source).toContain('sctVolume.toLocaleString(numberLocale')
    expect(source).toContain('toLocaleString(numberLocale)')
    expect(source).toContain('toLocaleDateString(numberLocale)')
    expect(source).not.toContain("toLocaleString('cs-CZ'")
    expect(source).not.toContain("toLocaleString(locale === 'cs' ? 'cs-CZ' : 'en-US'")
  })
})
