import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('party detail refresh contract', () => {
  it('prevents overlapping PII/KYC refreshes and exposes localized busy semantics', () => {
    const source = readFileSync(
      path.resolve(__dirname, '../app/parties/[id]/page.tsx'),
      'utf8',
    )

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit detail subjektu', 'Refresh party detail')}")
    expect(source).toContain("svcUrl('party-service'")
    expect(source).toContain("svcUrl('kyc-service'")
  })
})
