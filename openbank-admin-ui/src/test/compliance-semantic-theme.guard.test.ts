import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/compliance/page.tsx'), 'utf8')

describe('compliance hub contract (ADR-0379)', () => {
  it('uses shared semantic status tokens without fixed presentation colours', () => {
    expect(page).not.toMatch(/['"]#[0-9a-f]{3,8}\b/i)
    expect(page).not.toContain('rgba(')
    expect(page).toContain('var(--info-bg)')
    expect(page).toContain('var(--info-border)')
  })

  it('renders the derived trace, never a hand-written requirement list', () => {
    expect(page).toContain("fetch('/api/compliance/trace'")
    expect(page).not.toContain('COMPLIANCE_AREAS')
    expect(page).not.toMatch(/\{\s*req:\s*\[/)
  })

  it('keeps the legal-scope disclaimer and states that code computes the verdict', () => {
    expect(page).toContain('does not establish legal compliance')
    expect(page).toContain('The verdict is computed by code, not by a person or an AI')
  })

  it('offers all three audience views and the auditor export', () => {
    for (const id of ["id: 'gaps'", "id: 'departments'", "id: 'audit'"]) expect(page).toContain(id)
    expect(page).toContain('/api/compliance/trace?download=1')
  })
})
