import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const sidebar = readFileSync(path.resolve(__dirname, '../components/layout/Sidebar.tsx'), 'utf8')
const styles = readFileSync(path.resolve(__dirname, '../components/layout/Sidebar.module.css'), 'utf8')

describe('sidebar product branding contract', () => {
  it('does not advertise a stale hard-coded platform version', () => {
    expect(sidebar).not.toContain('OpenBank v2.0')
    expect(sidebar).toContain("OpenBank Admin Portal")
    expect(sidebar).toContain("OpenBank Admin portál")
  })

  it('keeps tiny section and compliance labels on the contrast-checked sidebar token', () => {
    expect(styles).toMatch(/\.sectionLabel\s*\{[\s\S]*?color:\s*var\(--sidebar-text-muted\)/)
    expect(styles).toMatch(/\.footerScope\s*\{[\s\S]*?color:\s*var\(--sidebar-text-muted\)/)
  })

  it('keeps the tiny live badge on the contrast-checked selected-control pair', () => {
    const badge = styles.match(/\.navBadge\s*\{([^}]*)\}/)?.[1]
    expect(badge).toContain('color: var(--text-inverse)')
    expect(badge).toContain('background: var(--selection-bg)')
  })
})
