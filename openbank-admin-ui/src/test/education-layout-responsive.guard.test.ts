// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('educational layouts on narrow screens', () => {
  it('stacks service documentation navigation without sticky mobile obstruction', () => {
    const page = readFileSync(path.resolve(__dirname, '../app/services/[name]/docs/[[...slug]]/page.tsx'), 'utf8')
    const styles = readFileSync(path.resolve(__dirname, '../app/services/[name]/docs/[[...slug]]/page.module.css'), 'utf8')
    expect(page).toContain('data-testid="service-docs-layout"')
    expect(styles).toMatch(/@media \(max-width: 840px\)[\s\S]*grid-template-columns: minmax\(0, 1fr\)/)
    expect(styles).toMatch(/@media \(max-width: 840px\)[\s\S]*position: static/)
  })

  it('stacks cluster defense visualization and explanation cards', () => {
    const page = readFileSync(path.resolve(__dirname, '../app/docs/cluster/page.tsx'), 'utf8')
    const styles = readFileSync(path.resolve(__dirname, '../app/docs/cluster/page.module.css'), 'utf8')
    expect(page).toContain('data-testid="cluster-defense-layout"')
    expect(styles).toMatch(/@media \(max-width: 720px\)[\s\S]*grid-template-columns: minmax\(0, 1fr\)/)
  })
})
