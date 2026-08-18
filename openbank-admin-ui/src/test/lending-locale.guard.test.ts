import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('lending locale formatting', () => {
  it('uses the active language for money and lifecycle timestamps', () => {
    for (const file of ['src/app/lending/page.tsx', 'src/app/lending/applications/[id]/page.tsx']) {
      const source = readFileSync(resolve(process.cwd(), file), 'utf8')
      expect(source).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
      expect(source).toContain('toLocaleString(numberLocale)')
      expect(source).toContain('toLocaleString(dateLocale)')
      expect(source).not.toContain("toLocaleString('cs-CZ'")
    }
  })
})
