// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/fx/page.tsx'), 'utf8')
const styles = readFileSync(path.resolve(__dirname, '../app/fx/page.module.css'), 'utf8')

describe('responsive FX schedule', () => {
  it('replaces the fixed six-column inline layout with labelled mobile evidence', () => {
    expect(page).not.toContain("gridTemplateColumns: '28px 1fr auto auto auto auto'")
    expect(page).toContain('className={styles.scheduleRow}')
    expect(page).toContain("data-label={t('Příští spuštění', 'Next run')}")
    expect(styles).toMatch(/@media \(max-width: 720px\)[\s\S]*\.scheduleRow[\s\S]*grid-template-columns: 28px minmax\(0, 1fr\)/)
    expect(styles).toContain('content: attr(data-label)')
    expect(page).toContain("aria-label={t('Posuvný kurzovní lístek ECB', 'Scrollable ECB rate sheet')}")
    expect(page).toContain("aria-label={t('Posuvná historie akcí operátora', 'Scrollable operator action log')}")
  })
})
