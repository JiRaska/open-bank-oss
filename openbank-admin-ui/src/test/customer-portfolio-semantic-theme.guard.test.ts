import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/party/CustomerPortfolioPanel.tsx'), 'utf8')

describe('Customer 360 portfolio semantic theme guard', () => {
  it('keeps unavailable evidence adaptive and actionable', () => {
    expect(source).not.toMatch(/#[0-9a-fA-F]{3,8}\b/u)
    expect(source).toContain("color: 'var(--warning-text)'")
    expect(source).toContain('role="status"')
    expect(source).toContain("t('Načíst znovu', 'Retry')")
    expect(source).toContain('clearSelectedCustomerGraphFacts(partyId)')
  })
})
