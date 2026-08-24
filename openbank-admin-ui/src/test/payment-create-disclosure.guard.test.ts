// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('payment create disclosure contract', () => {
  it('keeps the create toggle and payment type panel related', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/payments/page.tsx'), 'utf8')
    expect(source).toContain('type="button" aria-expanded={showCreate === \'payment-type\'} aria-controls="payment-create-type-panel"')
    expect(source).toContain('id="payment-create-type-panel"')
    expect(source).toContain("onClick={() => setShowCreate(showCreate ? null : 'payment-type')}")
  })
})
