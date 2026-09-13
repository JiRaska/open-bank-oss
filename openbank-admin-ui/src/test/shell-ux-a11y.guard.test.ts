import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const header = readFileSync(resolve(__dirname, '../components/layout/Header.tsx'), 'utf8')
const sidebar = readFileSync(resolve(__dirname, '../components/layout/Sidebar.tsx'), 'utf8')

describe('shared shell interaction and icon accessibility contract', () => {
  it('keeps the account menu connected to its opener and keyboard-dismissible', () => {
    expect(header).toContain('userMenuButtonRef')
    expect(header).toContain('userMenuRef')
    expect(header).toContain("aria-controls={menuOpen ? 'admin-user-menu' : undefined}")
    expect(header).toContain('id="admin-user-menu"')
    expect(header).toContain("event.key === 'Escape'")
    expect(header).toContain("role=\"menuitem\"")
    expect(header).toContain('userMenuButtonRef.current?.focus()')
  })

  it('names the language action and hides shell decoration from assistive tech', () => {
    expect(header).toContain('Přepnout na angličtinu')
    expect(header).toContain('Switch to Czech')
    expect(header).toMatch(/<Search[^>]*aria-hidden="true"/)
    expect(header).toMatch(/<LogOut[^>]*aria-hidden="true"/)
    expect(sidebar).toMatch(/<svg aria-hidden="true"/)
    expect(sidebar).toMatch(/<Icon aria-hidden="true" size=\{16\}/)
    expect(sidebar).toMatch(/<Lock aria-hidden="true"/)
  })
})
