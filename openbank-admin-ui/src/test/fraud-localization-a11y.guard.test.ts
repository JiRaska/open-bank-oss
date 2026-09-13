import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/fraud/page.tsx'), 'utf8')

describe('fraud queue presentation', () => {
  it('uses the active operator locale for money and timestamps', () => {
    expect(page).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(page).toContain('r.amount.toLocaleString(numberLocale)')
    expect(page).toContain('new Date(r.createdAt).toLocaleString(numberLocale)')
  })

  it('hides decorative icons while retaining their button text', () => {
    expect(page).toContain('<ShieldAlert size={20} aria-hidden="true"')
    expect(page).toContain('<RefreshCw size={14} aria-hidden="true"')
  })
})
