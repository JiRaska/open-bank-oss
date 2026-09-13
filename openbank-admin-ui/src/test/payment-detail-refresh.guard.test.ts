// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('payment detail refresh contract', () => {
  it('exposes localized busy semantics while refreshing the selected payment by id', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/payments/[id]/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit platbu', 'Refresh payment')}")
    expect(source).toContain('onClick={() => { void load() }}')
    expect(source).toContain("svcUrl(target.service, `${target.path}/${encodeURIComponent(id)}`)")
    expect(source).not.toContain('items.find')
  })
})
