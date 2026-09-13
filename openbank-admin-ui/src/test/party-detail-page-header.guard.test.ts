import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('party detail header contract', () => {
  it('uses the shared header for loading, unavailable, and loaded states', () => {
    const page = readFileSync(resolve(__dirname, '../app/parties/[id]/page.tsx'), 'utf8')
    expect(page).toContain('<PageHeader')
    expect(page).not.toContain('className="page-header"')
    expect(page).toContain('aria-hidden="true"')
  })
})
