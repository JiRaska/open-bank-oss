import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const pages = [
  'system/agent/page.tsx',
  'system/config/page.tsx',
  'temporal/flow/page.tsx',
].map(file => readFileSync(path.resolve(__dirname, '../app', file), 'utf8'))

describe('system operations page header contract', () => {
  it('uses the shared header and valid breadcrumb wrapper', () => {
    for (const page of pages) {
      expect(page).toContain('<PageHeader')
      expect(page).toContain('breadcrumb={<div className="breadcrumb">')
      expect(page).not.toContain('className="page-header"')
    }
  })

  it('keeps decorative icons hidden and native refresh controls wired', () => {
    for (const page of pages) expect(page).toContain('aria-hidden="true"')
    expect(pages[0]).toContain('onClick={loadTools}')
    expect(pages[1]).toContain('fetchAllServiceConfigSnapshots')
    expect(pages[2]).toContain('onClick={load}')
  })
})
