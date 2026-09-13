// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('onboarding list loading contract', () => {
  it('marks the existing records table busy during refresh', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/onboarding/page.tsx'), 'utf8')
    expect(source).toContain('<div className="card" aria-busy={loading}')
    expect(source).toContain('aria-label={t(\'Obnovit onboarding\', \'Refresh onboarding\')}')
    expect(source).toContain('setLoading(true)')
  })
})
