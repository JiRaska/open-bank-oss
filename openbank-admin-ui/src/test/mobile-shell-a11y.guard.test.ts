import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(process.cwd(), 'src')
const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8')

describe('mobile operator shell accessibility contract', () => {
  it('exposes a labelled drawer toggle and dismissible mobile navigation', () => {
    const appShell = read('components/layout/AppShell.tsx')
    const header = read('components/layout/Header.tsx')
    const sidebar = read('components/layout/Sidebar.tsx')
    const css = read('app/globals.css')
    expect(appShell).toContain("'use client'")
    expect(appShell).toContain('onKeyDown')
    expect(appShell).toContain("#admin-sidebar a, #admin-sidebar button")
    expect(appShell).toContain('cancelAnimationFrame')
    expect(appShell).toContain("event.key !== 'Tab'")
    expect(appShell).toContain('focusable.at(-1)')
    expect(appShell).toContain("document.querySelector('#admin-sidebar')?.contains(document.activeElement)")
    expect(appShell).toContain('ob-mobile-nav-overlay')
    expect(appShell).toContain("t('Zavřít navigaci', 'Close navigation')")
    expect(header).toContain('aria-controls="admin-sidebar"')
    expect(header).toContain('aria-expanded={mobileNavOpen}')
    expect(sidebar).toContain('id="admin-sidebar"')
    expect(sidebar).toContain('aria-label={t(')
    expect(css).toContain('@media (max-width: 860px)')
    expect(css).toContain('.ob-mobile-nav-overlay')
  })
})
