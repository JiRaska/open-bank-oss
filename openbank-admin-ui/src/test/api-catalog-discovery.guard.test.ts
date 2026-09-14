import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/api/page.tsx'), 'utf8')

it('uses theme-aware semantic colours for API methods and response states', () => {
  expect(page).toContain("get: { bg: 'var(--info-bg)', text: 'var(--info-text)', border: 'var(--info-border)' }")
  expect(page).toContain("post: { bg: 'var(--success-bg)', text: 'var(--success-text)', border: 'var(--success-border)' }")
  expect(page).toContain("delete: { bg: 'var(--danger-bg)', text: 'var(--danger-text)', border: 'var(--danger-border)' }")
  expect(page).not.toContain("background: '#fff'")
  expect(page).not.toContain("background: '#fef9c3'")
})

describe('API catalog discovery controls', () => {
  it('keeps service search and filter state discoverable and keyboard-safe', () => {
    expect(page).toContain('id="api-catalog-search"')
    expect(page).toContain('Search service or endpoint')
    expect(page).toContain('role="group" aria-label={t(\'Filtrovat podle domény\', \'Filter by domain\')')
    expect(page).toContain('aria-pressed={groupFilter === g}')
    expect(page).toContain('aria-pressed={activeTab === tab}')
    expect(page).toContain('type="button" aria-pressed={groupFilter === g}')
    expect(page).toContain('type="button" aria-pressed={activeTab === tab}')
    expect(page).toContain('type="button" className="btn btn-secondary" onClick={load}')
    expect(page).toContain('aria-busy={loading}')
    expect(page).toContain('<RefreshCw aria-hidden="true"')
    expect(page).toContain('No services match this filter')
    expect(page).toContain('status?.paths ?? []')
  })
})
