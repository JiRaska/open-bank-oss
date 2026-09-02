// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// RTL coverage for the ⌘K palette (ADR-0228 D3): open/close, debounced query, grouped results,
// keyboard navigation, recents. Fetch and next/navigation are mocked.

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { CommandPalette } from '@/components/search/CommandPalette'

const push = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push }),
}))

const PARTY = { type: 'party', id: 'p-1', label: 'Jan Novák', sublabel: 'INDIVIDUAL · ACTIVE', route: '/parties/p-1' }
const ACCOUNT = { type: 'account', id: 'a-9', label: '192000145399/0800', route: '/accounts/a-9' }

function renderPalette(open = true, onClose = vi.fn()) {
  return render(
    <LanguageProvider>
      <CommandPalette open={open} onClose={onClose} />
    </LanguageProvider>,
  )
}

describe('CommandPalette (ADR-0228 D3)', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ results: [PARTY, ACCOUNT] }), { status: 200 }),
    ))
  })
  afterEach(() => {
    vi.restoreAllMocks()
    push.mockClear()
  })

  it('renders nothing when closed', () => {
    renderPalette(false)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('queries the facade debounced once the term reaches 2 chars and shows grouped results', async () => {
    const user = userEvent.setup()
    renderPalette()
    await user.type(screen.getByRole('textbox'), 'nov')
    await waitFor(() => expect(screen.getByText('Jan Novák')).toBeTruthy(), { timeout: 2000 })
    expect(screen.getByText('192000145399/0800')).toBeTruthy()
    const calls = vi.mocked(fetch).mock.calls.filter(([u]) => String(u).includes('entities/resolve'))
    expect(calls).toHaveLength(1)
    expect(String(calls[0][0])).toContain('q=nov')
  })

  it('does not query below 2 chars — shows recents instead', async () => {
    sessionStorage.setItem('ob.palette.recents', JSON.stringify([PARTY]))
    const user = userEvent.setup()
    renderPalette()
    await user.type(screen.getByRole('textbox'), 'n')
    await new Promise(r => setTimeout(r, 500))
    expect(vi.mocked(fetch).mock.calls.filter(([u]) => String(u).includes('entities/resolve'))).toHaveLength(0)
    expect(screen.getByText('Jan Novák')).toBeTruthy()
  })

  it('distinguishes an unavailable search from a valid empty result and retries the same query', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ results: [PARTY] }), { status: 200 }))
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('textbox'), 'nov')
    expect(screen.getByRole('listbox')).toHaveAttribute('aria-busy', 'true')
    expect(await screen.findByRole('alert')).toHaveTextContent(/dočasně nedostupné|temporarily unavailable/i)
    expect(screen.queryByText(/nic nenalezeno|no results/i)).toBeNull()

    await user.click(screen.getByRole('button', { name: /zkusit znovu|try again/i }))
    expect(screen.getByRole('listbox')).toHaveAttribute('aria-busy', 'true')
    expect(await screen.findByText('Jan Novák')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('textbox')).toHaveValue('nov')
  })

  it('announces a successful empty result only after loading finishes', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ results: [] }), { status: 200 }),
    )
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('textbox'), 'zz')
    expect(screen.getByRole('status')).toHaveTextContent(/hledám|searching/i)
    expect(await screen.findByText(/nic nenalezeno|no results/i)).toBeTruthy()
    expect(screen.getByRole('listbox')).toHaveAttribute('aria-busy', 'false')
  })

  it('navigates with arrows and opens the active result on Enter, storing it in recents', async () => {
    const user = userEvent.setup()
    renderPalette()
    await user.type(screen.getByRole('textbox'), 'nov')
    await waitFor(() => expect(screen.getByText('Jan Novák')).toBeTruthy(), { timeout: 2000 })
    fireEvent.keyDown(window, { key: 'ArrowDown' })
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(push).toHaveBeenCalledWith('/accounts/a-9')
    const recents = JSON.parse(sessionStorage.getItem('ob.palette.recents') ?? '[]')
    expect(recents[0].id).toBe('a-9')
  })

  // #3886 regression, deterministic. The flake (`push` never called, 2.6% of CI runs) was the
  // rare, load-dependent form of this: a keydown reaching the palette while `shown` is still
  // empty. ArrowDown used to compute `Math.min(a + 1, shown.length - 1)` = -1, deselecting every
  // row for good — `push` is then never called, because `shown[-1]` is undefined. Pressing ↓
  // during the 300 ms debounce reproduces that window on purpose, with no timing luck involved.
  it('survives an ArrowDown pressed before results arrive — no out-of-range selection (#3886)', async () => {
    const user = userEvent.setup()
    renderPalette()
    await user.type(screen.getByRole('textbox'), 'nov')
    // Still inside DEBOUNCE_MS: the list is empty and no row exists yet.
    expect(screen.queryAllByRole('option')).toHaveLength(0)
    fireEvent.keyDown(window, { key: 'ArrowDown' })
    await waitFor(() => expect(screen.getByText('Jan Novák')).toBeTruthy(), { timeout: 2000 })
    const rows = screen.getAllByRole('option')
    expect(rows.map(r => r.getAttribute('aria-selected'))).toEqual(['true', 'false'])
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(push).toHaveBeenCalledWith('/parties/p-1')
  })

  it('highlights the active row with a balanced var() — an unclosed one is dropped by real CSS parsers', async () => {
    const user = userEvent.setup()
    renderPalette()
    await user.type(screen.getByRole('textbox'), 'nov')
    await waitFor(() => expect(screen.getByText('Jan Novák')).toBeTruthy(), { timeout: 2000 })
    const rows = screen.getAllByRole('option')
    expect(rows[0].getAttribute('aria-selected')).toBe('true')
    expect((rows[0] as HTMLElement).style.background).toBe('var(--sidebar-active-bg)')
    expect((rows[1] as HTMLElement).style.background).toBe('transparent')
  })

  it('closes on Escape', async () => {
    const onClose = vi.fn()
    renderPalette(true, onClose)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })

  it('keeps focus inside the modal and restores the opener on close', async () => {
    const user = userEvent.setup()
    const opener = document.createElement('button')
    opener.type = 'button'
    document.body.appendChild(opener)
    opener.focus()
    const onClose = vi.fn()
    const { rerender } = renderPalette(true, onClose)
    await waitFor(() => expect(screen.getByRole('textbox')).toHaveFocus())
    await user.tab({ shift: true })
    expect(screen.getByRole('button', { name: /zavřít|close/i })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('textbox')).toHaveFocus()
    rerender(<LanguageProvider><CommandPalette open={false} onClose={onClose} /></LanguageProvider>)
    expect(opener).toHaveFocus()
    opener.remove()
  })

  it('exposes a labelled listbox and active descendant for assistive technology', () => {
    renderPalette()
    expect(screen.getByRole('listbox', { name: /výsledky|search results/i })).toBeTruthy()
    expect(screen.getByRole('textbox')).not.toHaveAttribute('aria-activedescendant')
  })
})
