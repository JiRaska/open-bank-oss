import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/accounts/page.tsx'), 'utf8')

describe('accounts list usability', () => {
  it('keeps the search and filters named, with actionable query feedback', () => {
    expect(page).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(page).toContain('id="accounts-query"')
    expect(page).toContain('aria-label={t(')
    expect(page).toContain('const queryHelpVisible = !ibanHint && !result && !unavailable')
    expect(page).toContain("aria-describedby={ibanHint ? 'accounts-query-error' : queryHelpVisible ? 'accounts-query-help' : undefined}")
    expect(page).toContain('id="accounts-query-error" role="alert"')
  })

  it('uses the active operator locale for account dates and hides decorative icons', () => {
    expect(page).toContain('toLocaleDateString(numberLocale)')
    expect(page).toContain('<Search size={13} aria-hidden="true"')
    expect(page).toContain('<Filter size={11} aria-hidden="true"')
  })

  it('offers the shared party resolver and removes the stale coming-soon claim', () => {
    expect(page).toContain("import { PartySearch, type PartyHit } from '@/components/party/PartySearch'")
    expect(page).toContain('<PartySearch')
    expect(page).toContain('void search(party.id)')
    expect(page).toContain('party search above')
    expect(page).not.toContain('coming soon')
    expect(page).not.toContain('v přípravě')
  })
})
