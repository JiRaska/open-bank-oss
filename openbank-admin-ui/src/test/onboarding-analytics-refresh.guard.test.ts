// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('Onboarding analytics refresh contract', () => {
  it('exposes localized busy semantics without changing analytics loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/onboarding/analytics/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit analytiku onboardingu', 'Refresh onboarding analytics')}")
    expect(source).toContain('<RefreshCw size={13} aria-hidden="true"')
    expect(source).toContain('onClick={load}')
  })
})
