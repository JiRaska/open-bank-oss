import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')

describe('product catalog lifecycle action accessibility', () => {
  it('names edit, status, and close actions and prevents form submission', () => {
    expect(page).toContain("aria-label={product.status === 'ACTIVE' ? t('Deaktivovat produkt', 'Deactivate product') : t('Aktivovat produkt', 'Activate product')}")
    expect(page).toContain("aria-label={t('Zavřít detail produktu', 'Close product details')}")
    expect(page).toContain("aria-label={product.status === 'ACTIVE' ? t('Nejprve deaktivujte produkt před úpravou', 'Deactivate product before editing') : t('Upravit produkt', 'Edit product')}")
    expect(page).toContain('<button type="button"')
    expect(page).toContain('onClick={() => requestLifecycleChange(p)}')
    expect(page).toContain('disabled={p.status === \'ACTIVE\'}')
  })
})
