// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('onboarding analytics loading contract', () => {
  it('announces loading without changing analytics filters or fetch flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/onboarding/analytics/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={20} aria-hidden="true"')
    expect(source).toContain('aria-label={t(\'Obnovit analytiku onboardingu\', \'Refresh onboarding analytics\')}')
    expect(source).toContain('onClick={load}')
  })
})
