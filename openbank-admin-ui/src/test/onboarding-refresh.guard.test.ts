// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('Onboarding refresh contract', () => {
  it('exposes localized busy semantics without changing onboarding loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/onboarding/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading || countsLoading}')
    expect(source).toContain('aria-busy={loading || countsLoading}')
    expect(source).toContain("aria-label={t('Obnovit onboarding', 'Refresh onboarding')}")
    expect(source).toContain('<RefreshCw size={13} aria-hidden="true"')
    expect(source).toContain('onClick={refresh}')
  })
})
