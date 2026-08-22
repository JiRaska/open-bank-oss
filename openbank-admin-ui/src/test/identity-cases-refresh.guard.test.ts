// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('Identity cases refresh contract', () => {
  it('exposes a localized refresh name without changing case loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/identity-cases/page.tsx'), 'utf8')
    expect(source).toContain("aria-label={t('Obnovit případy identity', 'Refresh identity cases')}")
    expect(source).toContain('onClick={load}')
    expect(source).toContain('aria-busy={loading}')
  })
})
