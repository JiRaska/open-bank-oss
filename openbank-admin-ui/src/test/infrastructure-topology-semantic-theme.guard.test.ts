import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/infrastructure/topology/page.tsx'), 'utf8')

describe('infrastructure topology semantic theme contract', () => {
  it('derives every architecture layer and edge category from adaptive theme tokens', () => {
    expect(page).toContain("aws:           { color: 'var(--warning)', textColor: 'var(--warning-text)'")
    expect(page).toContain("platform:      { color: 'var(--accent)', textColor: 'var(--accent-text)'")
    expect(page).toContain("data:          { color: 'var(--info)', textColor: 'var(--info-text)'")
    expect(page).toContain("observability: { color: 'var(--success)', textColor: 'var(--success-text)'")
    expect(page).toContain("{ control: 'var(--accent)', data: 'var(--info)', flow: 'var(--success)' }")
    expect(page).toContain("{ control: 'var(--accent-text)', data: 'var(--info-text)', flow: 'var(--success-text)' }")
  })

  it('uses token-safe color mixing and contains no fixed presentation colors', () => {
    expect(page).toContain('color-mix(in srgb,')
    expect(page).toContain("'var(--text-inverse)'")
    expect(page).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(page).not.toMatch(/rgba?\(/i)
    expect(page).not.toContain('mixHex')
  })
})
