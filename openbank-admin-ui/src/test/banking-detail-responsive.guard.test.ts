// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('banking decision evidence on narrow screens', () => {
  it('stacks lending review and detail facts without losing long evidence', () => {
    const page = readFileSync(path.resolve(__dirname, '../app/lending/compliance-packs/page.tsx'), 'utf8')
    const styles = readFileSync(path.resolve(__dirname, '../app/lending/compliance-packs/page.module.css'), 'utf8')
    expect(page).toContain('data-testid="compliance-pack-review-details"')
    expect(page).toContain('role="alert" data-testid="decision-review-error"')
    expect(styles).toContain('overflow-wrap: anywhere')
    expect(styles).toMatch(/@media \(max-width: 560px\)[\s\S]*\.reviewDetails,[\s\S]*\.detailFacts[\s\S]*grid-template-columns: minmax\(0, 1fr\)/)
  })

  it('stacks read-only delegation approval facts', () => {
    const page = readFileSync(path.resolve(__dirname, '../app/approvals/delegation/[id]/page.tsx'), 'utf8')
    const styles = readFileSync(path.resolve(__dirname, '../app/approvals/delegation/[id]/page.module.css'), 'utf8')
    expect(page).toContain('data-testid="delegation-approval-facts"')
    expect(styles).toContain('overflow-wrap: anywhere')
    expect(styles).toMatch(/@media \(max-width: 560px\)[\s\S]*grid-template-columns: minmax\(0, 1fr\)/)
  })

  it('stacks the full delegation grant evidence', () => {
    const page = readFileSync(path.resolve(__dirname, '../app/delegations/[id]/page.tsx'), 'utf8')
    const styles = readFileSync(path.resolve(__dirname, '../app/delegations/[id]/page.module.css'), 'utf8')
    expect(page).toContain('data-testid="delegation-detail-facts"')
    expect(styles).toContain('overflow-wrap: anywhere')
    expect(styles).toMatch(/@media \(max-width: 560px\)[\s\S]*grid-template-columns: minmax\(0, 1fr\)/)
  })
})
