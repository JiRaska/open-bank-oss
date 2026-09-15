// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const header = readFileSync(path.resolve(__dirname, '../components/layout/Header.tsx'), 'utf8')
const styles = readFileSync(path.resolve(__dirname, '../components/layout/Header.module.css'), 'utf8')

describe('shared header semantic theme contract', () => {
  it('keeps shell glass, focus and destructive actions adaptive', () => {
    expect(styles).toContain('color-mix(in srgb, var(--surface) 90%, transparent)')
    expect(styles).toContain('.logoutButton:hover { background: var(--danger-bg); }')
    expect(styles).toContain('color: var(--danger-text);')
    expect(styles).toContain('box-shadow: 0 0 0 2px var(--danger-border)')
    expect(header).toContain('className={styles.logoutButton}')
    expect(header).toContain("fontSize: '10px', fontWeight: 600, lineHeight: 1.2,\n                  color: 'var(--text-secondary)',")
    expect(header).not.toMatch(/['"]#[0-9a-f]{3,8}\b/iu)
    expect(header).not.toMatch(/\$\{info\.color\}[0-9a-f]{2}/iu)
  })
})
