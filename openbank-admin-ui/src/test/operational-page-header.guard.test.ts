import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const pages = [
  'infrastructure/topology/page.tsx',
  'observability/traces/page.tsx',
  'onboarding/analytics/page.tsx',
  'swift/[id]/page.tsx',
].map(file => readFileSync(path.resolve(__dirname, '../app', file), 'utf8'))

describe('operational page header contract', () => {
  it('uses the shared header and valid breadcrumb wrapper on every migrated page', () => {
    for (const page of pages) {
      expect(page).toContain('<PageHeader')
      expect(page).toContain('breadcrumb={<div className="breadcrumb">')
      expect(page).not.toContain('className="page-header"')
    }
  })

  it('marks relocated decorative icons hidden while preserving native controls', () => {
    for (const page of pages) expect(page).toContain('aria-hidden="true"')
    expect(pages[1]).toContain('onClick={loadTraces}')
    expect(pages[2]).toContain('onClick={load}')
    expect(pages[3]).toContain('href="/swift"')
  })
})
