// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('product catalog actions contract', () => {
  it('keeps create and row lifecycle actions explicit and accessible', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')
    expect(source).toContain('type="button" className="btn btn-primary" onClick={openCreateModal}')
    expect(source).toContain("aria-label={t('Vytvořit nový produkt', 'Create new product')}")
    expect(source).toContain('<Edit size={13} aria-hidden="true" />')
    expect(source).toContain('aria-hidden="true" /> : <Play size={13} aria-hidden="true" />')
    expect(source).toContain('onClick={() => requestLifecycleChange(p)}')
    expect(source).toContain("role=\"dialog\"")
    expect(source).toContain('aria-modal="true"')
    expect(source).toContain('onClick={confirmLifecycleChange}')
    expect(source).toContain("const action = p.status === 'ACTIVE' ? 'deactivate' : 'activate'")
    expect(source).toContain("headers: { 'If-Match': `\"${p.revision}\"` }")
  })
})
