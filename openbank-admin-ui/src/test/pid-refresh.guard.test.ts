// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('PID refresh contract', () => {
  it('exposes localized busy semantics without changing PID loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/pid/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit PID záznamy', 'Refresh PID records')}")
    expect(source).toContain('<RefreshCw size={13} aria-hidden="true"')
    expect(source).toContain('onClick={load}')
  })
})
