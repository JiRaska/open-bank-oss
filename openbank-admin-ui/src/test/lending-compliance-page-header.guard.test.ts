import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/lending/compliance-packs/page.tsx'), 'utf8')

describe('lending compliance page header contract', () => {
  it('uses the shared header and preserves the compliance breadcrumb', () => {
    expect(page).toContain('<PageHeader')
    expect(page).toContain('breadcrumb={<div className="breadcrumb">')
    expect(page).not.toContain('className="page-header"')
  })

  it('keeps the maker-checker actions and decorative icon semantics', () => {
    expect(page).toContain('aria-hidden="true"')
    expect(page).toContain('PACKS_BASE}/proposals')
    expect(page).toContain('PACKS_BASE}/proposals/${id}/decide')
    expect(page).toContain('onClick={() => void load()}')
  })
})
