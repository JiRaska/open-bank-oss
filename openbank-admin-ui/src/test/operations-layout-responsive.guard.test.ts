// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (relativePath: string) => readFileSync(path.resolve(__dirname, relativePath), 'utf8')

describe('responsive operations evidence layouts', () => {
  it('stacks BCP incident classification with visible field labels', () => {
    const page = read('../app/docs/bcp/page.tsx')
    const styles = read('../app/docs/bcp/page.module.css')
    expect(page).toContain('className={styles.incidentClassification}')
    expect(page).toContain("data-label={t('Závažnost', 'Severity')}")
    expect(page).toContain("aria-label={t('Posuvný plán testování BCP', 'Scrollable BCP testing schedule')}")
    expect(page).toContain('tabIndex={0}')
    expect(styles).toMatch(/@media \(max-width: 640px\)[\s\S]*\.incidentClassification[\s\S]*grid-template-columns: 1fr/)
  })

  it('stacks incident filters while preserving the locally scrollable register', () => {
    const page = read('../app/security/incidents/page.tsx')
    const styles = read('../app/security/incidents/page.module.css')
    expect(page).toContain('className={styles.incidentFilters}')
    expect(page).toContain("aria-label={t('Posuvný registr ICT incidentů', 'Scrollable ICT incident register')}")
    expect(styles).toMatch(/@media \(max-width: 720px\)[\s\S]*\.incidentFilters[\s\S]*grid-template-columns: 1fr/)
  })

  it('turns the FinOps agent matrix into labelled evidence cards', () => {
    const page = read('../app/finops/page.tsx')
    const styles = read('../app/finops/page.module.css')
    expect(page).toContain('className={styles.agentCostRow}')
    expect(page).toContain("data-label={t('Náklady 7d', 'Cost 7d')}")
    expect(styles).toMatch(/@media \(max-width: 720px\)[\s\S]*\.agentCostHeader[\s\S]*display: none/)
    expect(styles).toContain('content: attr(data-label)')
  })
})
