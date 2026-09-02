// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('product catalog detail controls contract', () => {
  it('keeps lifecycle status and close controls explicit', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')
    expect(source).toContain('type="button" aria-pressed={product.status === \'ACTIVE\'}')
    expect(source).toContain('<Square size={11} aria-hidden="true" />')
    expect(source).toContain('<Play size={11} aria-hidden="true" />')
    expect(source).toContain('<X size={18} aria-hidden="true" />')
  })
})
