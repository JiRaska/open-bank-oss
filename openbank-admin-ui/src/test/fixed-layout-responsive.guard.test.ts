// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('fixed asymmetric layouts', () => {
  it('stacks security results and keeps the evidence table locally scrollable', () => {
    const page = readFileSync(path.resolve(__dirname, '../app/security/page.tsx'), 'utf8')
    const styles = readFileSync(path.resolve(__dirname, '../app/security/page.module.css'), 'utf8')
    expect(page).toContain('className={styles.resultsTableViewport}')
    expect(page).toContain('tabIndex={0}')
    expect(styles).toContain('overflow: auto')
    expect(styles).toMatch(/@media \(max-width: 1100px\)[\s\S]*grid-template-columns: minmax\(0, 1fr\)/)
  })

  it('stacks process education and long token claims', () => {
    const page = readFileSync(path.resolve(__dirname, '../components/docs/ProcessView.tsx'), 'utf8')
    const styles = readFileSync(path.resolve(__dirname, '../components/docs/ProcessView.module.css'), 'utf8')
    expect(page).toContain('className={styles.processLayout}')
    expect(page).toContain('className={styles.claimRow}')
    expect(styles).toMatch(/@media \(max-width: 900px\)[\s\S]*grid-template-columns: minmax\(0, 1fr\)/)
    expect(styles).toContain('overflow-wrap: anywhere')
  })
})
