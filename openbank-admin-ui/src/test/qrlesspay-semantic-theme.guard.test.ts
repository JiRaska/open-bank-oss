import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/qrlesspay/page.tsx'), 'utf8')

describe('QR-less payment education semantic theme contract', () => {
  it('uses semantic tokens throughout status and sequence presentation', () => {
    expect(page).not.toMatch(/['"]#[0-9a-f]{3,8}\b/i)
    expect(page).not.toContain('rgba(')
    expect(page).not.toMatch(/var\(--[^,)]+,\s*#[0-9a-f]{3,8}/i)
    for (const token of ['accent', 'success', 'warning']) {
      expect(page).toContain(`var(--${token}-text)`)
    }
    expect(page).toContain('var(--accent)')
    expect(page).toContain('var(--success)')
  })

  it('preserves money-path, confirmation and cryptographic guidance', () => {
    for (const term of ['money-path', 'Ed25519', 'nonce + exp', 'SCA', 'VOP', 'RSSI', 'UWB', 'ADR-0095']) {
      expect(page).toContain(term)
    }
    expect(page).toContain('No money moves without payer confirmation')
    expect(page).toContain('role="img"')
  })
})
