import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('ledger locale formatting', () => {
  it('uses the active language for monetary values', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/app/ledger/page.tsx'), 'utf8')
    expect(source).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(source).toContain('toLocaleString(numberLocale, { minimumFractionDigits: 2 })')
    expect(source).not.toContain("toLocaleString('en-US'")
  })
})
