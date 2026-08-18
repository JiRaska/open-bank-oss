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
})
