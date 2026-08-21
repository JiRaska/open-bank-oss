import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('KYC search and refresh contract', () => {
  it('prevents overlapping case loads and exposes localized busy semantics', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/kyc/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit KYC případy', 'Refresh KYC cases')}")
    expect(source).toContain("aria-label={t('Vyhledat KYC případy', 'Search KYC cases')}")
    expect(source).toContain("${KYC_SERVICE}/api/v1/kyc/cases")
  })
})
