import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/customer-app/page.tsx'), 'utf8')

describe('customer app dossier semantic theme contract', () => {
  it('uses semantic tokens for status, lens and decision presentation', () => {
    expect(page).not.toMatch(/['"]#[0-9a-f]{3,8}\b/i)
    expect(page).not.toContain('rgba(')
    expect(page).not.toMatch(/var\(--[^,)]+,\s*#[0-9a-f]{3,8}/i)
    expect(page).toContain('color-mix(in srgb')
    for (const token of ['success', 'warning', 'danger', 'info', 'accent']) {
      expect(page).toContain(`var(--${token}-text)`)
    }
  })

  it('preserves honest plan-vs-reality and security evidence', () => {
    for (const term of ['AppConfig.kt', 'app-status.yaml', 'ADR-0074', 'certPinningActive', 'decisionMissing', 'governance', 'technology', 'security']) {
      expect(page).toContain(term)
    }
    expect(page).toContain('Facts derived from code, not hand-transcribed')
    expect(page).toContain("fetch('/api/app-status'")
  })
})
