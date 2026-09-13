import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('sanctions review form accessibility', () => {
  it('associates decision and screening controls with labels', () => {
    const page = readFileSync(resolve(__dirname, '../app/sanctions/page.tsx'), 'utf8')
    for (const field of ['review-status', 'review-note', 'search-name', 'search-type', 'search-dob', 'search-nationality']) {
      expect(page).toContain(`htmlFor="sanctions-${field}"`)
      expect(page).toContain(`id="sanctions-${field}"`)
    }
    expect(page).toContain('id="sanctions-approval-id"')
    expect(page).toContain("aria-label={t('ID žádosti', 'Approval id')}")
  })
})
