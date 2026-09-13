import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('campaign brief accessibility', () => {
  it('names brief fields and exposes pressed state for choice tiles', () => {
    const page = readFileSync(resolve(__dirname, '../app/campaigns/new/page.tsx'), 'utf8')
    expect(page).toContain('id="c-name"')
    expect(page).toContain("aria-label={t('Název kampaně', 'Campaign name')}")
    expect(page).toContain('id="c-goal"')
    expect(page).toContain("aria-label={t('Cíl kampaně', 'Campaign goal')}")
    // ADR-0269 (#8773): the credit selector is part of the brief, so it is labelled like the rest
    // of it. A required control that only screen-reader users cannot identify is a required
    // control they cannot submit the form past.
    expect(page).toContain('id="c-product-kind"')
    expect(page).toContain("aria-label={t('Typ úvěrového produktu', 'Credit product kind')}")
    expect(page).toContain('aria-pressed={active}')
    expect(page).toContain("data-entry-pick=\"SCHEDULE\"")
    expect(page).toContain("aria-pressed={entryMode === 'SCHEDULE'}")
    expect(page).toContain("aria-pressed={entryMode === 'TRIGGER'}")
    expect(page).toContain("aria-pressed={entryMode === 'MANUAL'}")
  })
})
