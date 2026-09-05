// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('product catalog row lifecycle state contract', () => {
  it('keeps row status toggles stateful and labeled', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')
    expect(source).toContain('type="button" aria-pressed={p.status === \'ACTIVE\'}')
    expect(source).toContain('onClick={() => requestLifecycleChange(p)}')
    expect(source).toContain("aria-label={p.status === 'ACTIVE' ? t('Deaktivovat produkt', 'Deactivate product') : t('Aktivovat produkt', 'Activate product')}")
  })
})
