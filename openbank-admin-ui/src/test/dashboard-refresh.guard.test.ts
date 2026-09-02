// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('dashboard refresh contract', () => {
  it('exposes localized busy semantics without changing parallel health reads', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/dashboard/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit přehled platformy', 'Refresh platform overview')}")
    expect(source).toContain('Promise.all')
    expect(source).toContain('onClick={load}')
  })
})
