// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('Product catalog refresh contract', () => {
  it('exposes localized busy semantics without changing catalog loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit katalog produktů', 'Refresh product catalog')}")
    expect(source).toContain('onClick={load}')
  })

  it('refreshes the latest selection without making selection a fetch dependency', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')
    expect(source).toContain('setSelectedProduct(current => current')
    expect(source).toContain('items.find(product => product.id === current.id) ?? current')
    expect(source).toContain('}, [])')
    expect(source).toContain('useEffect(() => { load() }, [load])')
    expect(source).not.toContain('}, [selectedProduct])')
  })
})
