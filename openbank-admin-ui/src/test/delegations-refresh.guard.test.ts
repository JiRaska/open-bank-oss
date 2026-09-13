// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('delegations refresh contract', () => {
  it('keeps the read-only refresh action explicit and accessible', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/delegations/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit delegovaný přístup', 'Refresh delegated access')}")
    expect(source).toContain('loadGrants(party)')
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true" />')
  })
})
