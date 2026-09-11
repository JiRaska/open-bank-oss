import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/compliance/page.tsx'), 'utf8')

describe('compliance education semantic theme contract', () => {
  it('uses shared semantic status tokens without fixed presentation colours', () => {
    // Issue references such as "#2370" are content, not colour literals.
    expect(page).not.toMatch(/['"]#[0-9a-f]{3,8}\b/i)
    expect(page).not.toContain('rgba(')
    for (const token of ['success', 'warning', 'danger', 'info', 'accent']) {
      expect(page).toContain(`var(--${token}-text)`)
      expect(page).toContain(`var(--${token}-bg)`)
      expect(page).toContain(`var(--${token}-border)`)
    }
  })

  it('preserves the regulatory education and technical disclaimer', () => {
    for (const framework of ['PSD2 / RTS on SCA', 'AML / 5AMLD / 6AMLD', 'KYC / CDD / EDD', 'GDPR', 'EBA ICT Risk Guidelines', 'FATCA / CRS']) {
      expect(page).toContain(framework)
    }
    expect(page).toContain('Full regulatory compliance also requires legal documentation')
    expect(page).toContain('issue #2370')
  })
})
