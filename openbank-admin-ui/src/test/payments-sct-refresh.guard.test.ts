// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('SCT Inst refresh contract', () => {
  it('exposes localized busy semantics without changing the SCT loader', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/payments/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={sctLoading}')
    expect(source).toContain('aria-busy={sctLoading}')
    expect(source).toContain("aria-label={t('Obnovit SCT platby', 'Refresh SCT payments')}")
    expect(source).toContain('<RefreshCw size={12} aria-hidden="true"')
    expect(source).toContain('onClick={loadSct}')
  })
})
