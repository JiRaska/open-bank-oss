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

  it('closes on Escape', async () => {
    const onClose = vi.fn()
    renderPalette(true, onClose)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })
})
