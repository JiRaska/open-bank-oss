// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/payments/page.tsx'), 'utf8')
const styles = readFileSync(path.resolve(__dirname, '../app/payments/page.module.css'), 'utf8')

describe('payment maker forms', () => {
  it('uses bounded responsive grids for every payment entry group', () => {
    expect(page).not.toMatch(/gridTemplateColumns: '1fr 1fr(?: 1fr)?'/)
    expect(page.match(/styles\.twoColumnGrid/g)?.length).toBeGreaterThanOrEqual(7)
    expect(page.match(/styles\.threeColumnGrid/g)).toHaveLength(2)
    expect(styles).toContain('repeat(2, minmax(0, 1fr))')
    expect(styles).toContain('repeat(3, minmax(0, 1fr))')
    expect(styles).toMatch(/@media \(max-width: 720px\)[\s\S]*grid-template-columns: minmax\(0, 1fr\)/)
  })

  it('keeps account-number controls shrinkable and mobile actions reachable', () => {
    expect(page.match(/styles\.accountNumberField/g)).toHaveLength(3)
    expect(styles).toContain('.accountNumberField > input')
    expect(styles).toContain('min-width: 0')
    expect(styles).toMatch(/@media \(max-width: 560px\)[\s\S]*\.formActions > \*[\s\S]*width: 100%/)
  })
})
