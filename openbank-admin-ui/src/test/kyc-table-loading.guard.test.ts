// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('KYC table loading contract', () => {
  it('marks the existing cases table busy during refresh/search', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/kyc/page.tsx'), 'utf8')
    expect(source).toContain('<div className="card" aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit KYC případy', 'Refresh KYC cases')}")
    expect(source).toContain("aria-label={t('Vyhledat KYC případy', 'Search KYC cases')}")
    expect(source).toContain('setLoading(true)')
  })
})
