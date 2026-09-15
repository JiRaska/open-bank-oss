// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (relative: string) => readFileSync(path.resolve(__dirname, relative), 'utf8')

describe('responsive educational evidence', () => {
  it('stacks shared sensor field explanations without losing long values', () => {
    const source = read('../components/docs/SensorFamilyView.tsx')
    const styles = read('../components/docs/SensorFamilyView.module.css')
    expect(source).toContain('className={styles.field}')
    expect(styles).toMatch(/@media \(max-width: 560px\)[\s\S]*\.field[\s\S]*grid-template-columns: 1fr/)
    expect(styles).toContain('overflow-wrap: anywhere')
  })

  it('stacks onboarding drawer facts and wraps evidence values', () => {
    const source = read('../app/onboarding/page.tsx')
    const styles = read('../app/onboarding/page.module.css')
    expect(source).toContain('className={styles.drawerRow}')
    expect(styles).toMatch(/@media \(max-width: 560px\)[\s\S]*\.drawerRow[\s\S]*grid-template-columns: 1fr/)
  })

  it('stacks agent findings and uses semantic security tones', () => {
    const source = read('../components/testing/TestAgentPanel.tsx')
    const styles = read('../components/testing/TestAgentPanel.module.css')
    expect(source).toContain('className={styles.finding}')
    expect(source).toContain("'var(--danger-text)' : 'var(--warning-text)'")
    expect(styles).toMatch(/@media \(max-width: 560px\)[\s\S]*\.finding/)
  })
})
