import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/identity-dedup/page.tsx'), 'utf8')

describe('identity deduplication semantic theme contract', () => {
  it('keeps presentation colours out of the educational content and diagrams', () => {
    expect(page).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(page).not.toMatch(/\$\{[^}]+\}(?:12|15|30)/)
  })

  it('uses shared semantic states and theme-aware translucent layers', () => {
    for (const token of ['success', 'warning', 'danger', 'info', 'accent', 'text-primary', 'text-secondary']) {
      expect(page).toContain(`var(--${token})`)
    }
    expect(page).toContain('color-mix(in srgb')
  })
})
