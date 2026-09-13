// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('BCP refresh contract', () => {
  it('exposes localized busy semantics without changing health probes', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/docs/bcp/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit stav BCP', 'Refresh BCP status')}")
    expect(source).toContain('onClick={fetchHealth}')
    expect(source).toContain('<RefreshCw size={13} aria-hidden="true"')
  })
})
