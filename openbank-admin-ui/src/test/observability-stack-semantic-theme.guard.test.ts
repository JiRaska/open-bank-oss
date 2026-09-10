import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/observability/stack/page.tsx'), 'utf8')

describe('observability stack semantic theme contract', () => {
  it('uses shared semantic colours for cards, diagrams and links', () => {
    expect(page).not.toMatch(/['"]#[0-9a-f]{3,8}\b/i)
    expect(page).not.toContain('rgba(')
    expect(page).not.toMatch(/var\(--[^,)]+,\s*#[0-9a-f]{3,8}/i)
    expect(page).toContain('color-mix(in srgb')
    for (const token of ['--accent', '--info', '--success', '--warning', '--danger', '--text-inverse']) {
      expect(page).toContain(`var(${token})`)
    }
  })

  it('preserves the trace correlation, on-call and synthetic education model', () => {
    for (const term of ['trace_id', 'X-Correlation-ID', 'Prometheus', 'Loki', 'Tempo', 'Pyroscope', 'Pyrra', 'GoAlert', 'ntfy', 'HolmesGPT']) {
      expect(page).toContain(term)
    }
    expect(page).toContain("href=\"/observability\"")
    expect(page).toContain('role="img"')
  })
})
