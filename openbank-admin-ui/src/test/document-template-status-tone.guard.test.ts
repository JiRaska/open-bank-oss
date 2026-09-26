// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('document template status presentation', () => {
  it('uses the shared semantic badge rather than a page-local colour map', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/document-templates/page.tsx'), 'utf8')
    const uiImports = source.match(/import \{([^}]*)\} from '@\/components\/ui'/)?.[1] ?? ''

    expect(uiImports).toMatch(/\bPageHeader\b/)
    expect(uiImports).toMatch(/\bStatusBadge\b/)
    expect(uiImports).toMatch(/\btype Tone\b/)
    expect(source).toContain("DRAFT: 'warning'")
    expect(source).toContain("PUBLISHED: 'success'")
    expect(source).toContain("RETIRED: 'neutral'")
    expect(source).toContain("<StatusBadge status={tpl.status ?? 'DRAFT'} tone={TEMPLATE_STATUS_TONE[tpl.status ?? 'DRAFT']} />")
    expect(source).not.toContain('STATUS_COLOR')
  })
})
