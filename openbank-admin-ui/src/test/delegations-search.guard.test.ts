// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('delegations search contract', () => {
  it('keeps party search and selection explicit', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/delegations/page.tsx'), 'utf8')
    expect(source).toContain('type="button" className="btn btn-primary" onClick={search}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Vyhledat delegující stranu', 'Search delegating party')}")
    expect(source).toContain('aria-pressed={party?.id === r.id}')
    expect(source).toContain('onClick={() => loadGrants(r)}')
  })
})
