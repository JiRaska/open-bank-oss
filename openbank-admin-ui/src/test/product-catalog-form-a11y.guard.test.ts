import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('product catalog editor accessibility', () => {
  it('associates every editable catalog field with its label', () => {
    const page = readFileSync(resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')
    for (const field of ['code', 'type', 'name', 'description', 'currency', 'status', 'version', 'base-rate', 'valid-from']) {
      expect(page).toContain(`htmlFor="catalog-${field}"`)
      expect(page).toContain(`id="catalog-${field}"`)
    }
  })
})
