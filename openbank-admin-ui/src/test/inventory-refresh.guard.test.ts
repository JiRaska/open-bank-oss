// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('service inventory refresh contract', () => {
  it('keeps refresh action explicit and exposes busy semantics', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/system/inventory/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={refreshing}')
    expect(source).toContain("aria-label={t('Obnovit inventář služeb', 'Refresh service inventory')}")
    expect(source).toContain('<RefreshCw size={12} aria-hidden="true"')
    expect(source).toContain('refresh(true)')
  })
})
