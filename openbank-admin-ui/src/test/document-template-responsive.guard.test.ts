// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/document-templates/page.tsx'), 'utf8')
const styles = readFileSync(path.resolve(__dirname, '../app/document-templates/page.module.css'), 'utf8')

describe('document template responsive workspace', () => {
  it('stacks the source and sandboxed preview with explicit mobile labels', () => {
    expect(page).toContain('data-testid="template-editor-workspace"')
    expect(page).toContain('data-testid="template-source-mobile-label"')
    expect(page).toContain('data-testid="template-preview-mobile-label"')
    expect(page).toContain('sandbox="allow-same-origin"')
    expect(styles).toContain('repeat(2, minmax(0, 1fr))')
    expect(styles).toMatch(/@media \(max-width: 720px\)[\s\S]*\.editorWorkspace[\s\S]*grid-template-columns: minmax\(0, 1fr\)/)
  })

  it('stacks document evidence and allows long identifiers to wrap', () => {
    expect(page).toContain('className={styles.documentDetails}')
    expect(page).toContain('className={styles.documentDetailValue}')
    expect(styles).toContain('overflow-wrap: anywhere')
    expect(styles).toMatch(/\.documentDetails[\s\S]*grid-template-columns: minmax\(0, 1fr\)/)
  })
})
