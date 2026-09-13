import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('operator date locale consistency', () => {
  it('uses the active language on KYC, onboarding and parties lists', () => {
    for (const route of ['kyc', 'onboarding', 'parties']) {
      const source = readFileSync(resolve(process.cwd(), `src/app/${route}/page.tsx`), 'utf8')
      expect(source).toContain("const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
      expect(source).toMatch(/toLocaleDateString\(dateLocale\)|toLocaleString\(dateLocale\)/)
      expect(source).not.toMatch(/toLocale(?:DateString|String)\(\)/)
    }
  })
})
