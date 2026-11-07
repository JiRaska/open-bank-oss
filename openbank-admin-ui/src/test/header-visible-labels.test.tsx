// SPDX-License-Identifier: Apache-2.0
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Header } from '@/components/layout/Header'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { name: 'Alex Example', roles: ['ROLE_VIEWER'] } } }),
  signOut: vi.fn(),
}))
vi.mock('@/lib/theme/useTheme', () => ({ useTheme: () => ({ theme: 'light', toggle: vi.fn() }) }))
vi.mock('@/components/search/CommandPalette', () => ({ CommandPalette: ({ open }: { open: boolean }) => open ? <div role="dialog">Search</div> : null }))
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
})

describe.each(['en', 'cs'] as const)('Header labels in %s', (language) => {
  function renderHeader() {
    render(<LanguageProvider initialLanguage={language}><Header /></LanguageProvider>)
  }

  it('includes the visible search label and preserves its shortcut', () => {
    renderHeader()
    const search = screen.getByRole('button', { name: language === 'en' ? 'Quick search (⌘K)' : 'Rychlé hledání (⌘K)' })
    expect(search).toHaveAccessibleName(language === 'en' ? 'Quick search (⌘K)' : 'Rychlé hledání (⌘K)')
    expect(search.textContent!.replace(/\s+/g, ' ').trim()).toBe(language === 'en' ? 'Quick search (⌘K)' : 'Rychlé hledání (⌘K)')
    expect(search.querySelector('kbd')).toHaveAttribute('aria-hidden', 'true')
    expect(search).toHaveAttribute('aria-keyshortcuts', 'Meta+K Control+K')
  })

  it('includes the visible language code and still switches languages', () => {
    renderHeader()
    const toggle = screen.getByRole('button', { name: language === 'en' ? /Switch to Czech/ : /Přepnout na angličtinu/ })
    expect(toggle).toHaveAccessibleName(expect.stringContaining(toggle.textContent!))
    fireEvent.click(toggle)
    expect(screen.getByRole('button', { name: language === 'en' ? 'CS: Přepnout na angličtinu' : 'EN: Switch to Czech' })).toBeInTheDocument()
  })

  it('includes all painted user text without duplicate initials', () => {
    renderHeader()
    const menu = screen.getByRole('button', { name: /(?:Open user menu|Otevřít uživatelskou nabídku)/ })
    expect(menu).toHaveAccessibleName(expect.stringContaining('Alex Example'))
    const role = menu.querySelectorAll('span')[1]!
    expect(menu).toHaveAccessibleName(expect.stringContaining(role.textContent!))
    expect(menu.textContent!.replace(/\s+/g, ' ').trim()).toBe(`Alex Example ${role.textContent}`)
    expect(menu).toHaveAccessibleName(expect.stringContaining(menu.textContent!.replace(/\s+/g, ' ').trim()))
    expect(menu.querySelector('svg.lucide-user-round')).toHaveAttribute('aria-hidden', 'true')
  })

  it('retains Meta+K and Control+K keyboard operation', () => {
    renderHeader()
    fireEvent.keyDown(window, { key: 'k', metaKey: true })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
