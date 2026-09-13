import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'src/app/finops/allocation/page.tsx'), 'utf8')

describe('finops allocation locale guard', () => {
  it('formats all allocation money values with the active locale', () => {
    expect(source).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(source).toContain('function money(n: number, locale: string)')
    expect(source).not.toContain('toLocaleString(undefined')
    expect(source).toContain('money(amount, numberLocale)')
  })
})
