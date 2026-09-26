// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('regulatory report disclosure contract', () => {
  it('uses a native disclosure button without nesting the export action', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/regulatory/page.tsx'), 'utf8')

    expect(source).toContain('<button type="button" aria-expanded={isSelected}')
    expect(source).toContain('aria-controls={`regulatory-report-${report.id}`}')
    expect(source).toContain('id={`regulatory-report-${report.id}`}')
    expect(source).not.toContain('<div role="button" tabIndex={0}')
    expect(source).toContain('Actions remain a sibling of the disclosure button')
  })
})
