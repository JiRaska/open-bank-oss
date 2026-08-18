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
    expect(page).toContain('aria-pressed={active}')
    expect(page).toContain("data-entry-pick=\"SCHEDULE\"")
    expect(page).toContain("aria-pressed={entryMode === 'SCHEDULE'}")
    expect(page).toContain("aria-pressed={entryMode === 'TRIGGER'}")
    expect(page).toContain("aria-pressed={entryMode === 'MANUAL'}")
  })
})
