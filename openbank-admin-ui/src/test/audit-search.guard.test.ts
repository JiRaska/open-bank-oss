// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('audit search contract', () => {
  it('keeps audit search explicit and truthful while loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/audit/page.tsx'), 'utf8')
    expect(source).toContain('type="button" className="btn btn-primary" onClick={search}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Vyhledat auditní záznam', 'Search audit trail')}")
    expect(source).toContain('disabled={loading || !aggregateId.trim()}')
  })
})
