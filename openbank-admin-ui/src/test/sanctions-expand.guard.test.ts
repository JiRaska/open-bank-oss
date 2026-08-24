// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('sanctions list disclosure contract', () => {
  it('keeps list details disclosure explicit and localized', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/sanctions/page.tsx'), 'utf8')
    expect(source).toContain('<button type="button" onClick={() => setExpanded(e => !e)}')
    expect(source).toContain('aria-expanded={expanded}')
    expect(source).toContain("t('Sbalit podrobnosti seznamu', 'Collapse list details')")
    expect(source).toContain("t('Rozbalit podrobnosti seznamu', 'Expand list details')")
    expect(source).toContain('setExpanded(e => !e)')
    expect(source).toContain('<ChevronUp size={14} aria-hidden="true" />')
    expect(source).toContain('<ChevronDown size={14} aria-hidden="true" />')
  })
})
