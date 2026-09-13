import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('product catalog detail accessibility', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/app/product-catalog/page.tsx'), 'utf8')

  it('exposes detail section state and names the editor close control', () => {
    expect(source).toContain("aria-label={t('Karty detailu produktu', 'Product detail sections')}")
    expect(source).toContain('aria-pressed={tab === tabItem.id}')
    expect(source).toContain("aria-label={t('Zavřít editor produktu', 'Close product editor')}")
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-hidden="true"')
  })
})
