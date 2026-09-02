// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('flaky test hunter loading contract', () => {
  it('announces loading and names refresh without changing governance flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/iaops/flaky-test-hunter/page.tsx'), 'utf8')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit flaky testy', 'Refresh flaky tests')}")
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('onClick={() => void load()}')
  })
})
