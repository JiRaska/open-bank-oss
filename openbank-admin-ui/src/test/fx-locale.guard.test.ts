import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('fx locale formatting', () => {
  it('uses the active language for volumes and quotes', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/app/fx/page.tsx'), 'utf8')
    expect(source).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(source).toContain('toLocaleString(numberLocale)')
    expect(source).toContain('toLocaleTimeString(numberLocale)')
    expect(source).not.toMatch(/toLocale(?:String|TimeString|DateString)\([^)]*['\"](?:cs-CZ|en-US)['\"]/)
  })
})
