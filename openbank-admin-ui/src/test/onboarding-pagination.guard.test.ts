// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('onboarding pagination contract', () => {
  it('keeps pagination controls explicit and named', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/onboarding/page.tsx'), 'utf8')
    expect(source).toContain("type=\"button\" className=\"btn btn-secondary\" aria-label={t('Předchozí strana onboardingu', 'Previous onboarding page')}")
    expect(source).toContain("aria-label={t('Další strana onboardingu', 'Next onboarding page')}")
    expect(source).toContain('onClick={() => setPage(p => p - 1)}')
    expect(source).toContain('onClick={() => setPage(p => p + 1)}')
  })
})
