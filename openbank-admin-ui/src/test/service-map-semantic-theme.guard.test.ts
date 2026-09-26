import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/service-map/page.tsx'), 'utf8')
const globals = readFileSync(join(process.cwd(), 'src/app/globals.css'), 'utf8')
const mapTokens = [
  'core', 'payment', 'compliance', 'identity', 'psd2', 'platform', 'cards',
  'infrastructure', 'external', 'edge-sync', 'edge-sync-active',
  'edge-async', 'edge-async-active', 'edge-db', 'edge-auth', 'edge-authz',
  'edge-push', 'edge-registry', 'edge-llm',
]

describe('service map semantic theme contract', () => {
  it('keeps presentation colours out of topology data and rendering', () => {
    expect(page).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(page).not.toMatch(/\$\{[^}]*color\}[0-9a-f]{2}/i)
    expect(page).toContain('color-mix(in srgb')
  })

  it('defines every map token for both light and dark themes', () => {
    const darkTheme = globals.slice(globals.indexOf('.dark {'))
    for (const token of mapTokens) {
      expect(globals).toContain(`--map-${token}:`)
      expect(darkTheme).toContain(`--map-${token}:`)
    }
  })
})
