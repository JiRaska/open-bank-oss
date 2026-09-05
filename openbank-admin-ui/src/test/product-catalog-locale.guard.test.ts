import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('product catalog locale formatting', () => {
  it('uses the active language for product money values', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/app/product-catalog/page.tsx'), 'utf8')
    expect(source).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(source).toContain('toLocaleString(numberLocale)')
    expect(source).not.toContain("toLocaleString('cs-CZ'")
  })

  it('keeps the detail, portfolio and editor copy behind the language boundary', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/app/product-catalog/page.tsx'), 'utf8')
    for (const literal of [
      'label="Základní informace"',
      'label="Konfigurace karet"',
      'label="Termínovaný vklad"',
      '>Interní<',
      '>Aktuální<',
      '>Zrušit<',
      "saving ? 'Ukládám…'",
      '<strong>Chyba:</strong>',
    ]) {
      expect(source).not.toContain(literal)
    }
    expect(source).toContain("t('Základní informace', 'Core information')")
    expect(source).toContain("t('Konfigurace karet', 'Card configuration')")
    expect(source).toContain("t('Historie verzí', 'Version history')")
    expect(source).toContain("t('Produkt se nepodařilo uložit.', 'Failed to save product.')")
  })
})
