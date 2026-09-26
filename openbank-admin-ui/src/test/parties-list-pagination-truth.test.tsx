// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PartiesPage from '@/app/parties/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { parsePartyListPage } from '@/lib/party/partyListContract'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_OPERATOR'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

const ids = [
  '24977cca-20b2-4877-80d1-403b40181a89',
  '77777777-7777-4777-8777-777777777777',
]

function item(id: string, legalName: string) {
  return {
    id, legalName, email: `${legalName.toLowerCase()}@example.test`, partyType: 'INDIVIDUAL',
    status: 'ACTIVE', kycStatus: 'APPROVED', createdAt: '2026-09-01T10:00:00Z',
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function mount() {
  return render(<LanguageProvider><PartiesPage /></LanguageProvider>)
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('party list pagination truth', () => {
  it('accepts only the requested, bounded page with valid party evidence', () => {
    const valid = { items: [item(ids[0], 'Ada')], total: 2, page: 0, size: 25 }
    expect(parsePartyListPage(valid, 0)).toMatchObject(valid)
    expect(parsePartyListPage({ ...valid, page: 1 }, 0)).toBeNull()
    expect(parsePartyListPage({ ...valid, items: [{ ...valid.items[0], createdAt: 'never' }] }, 0)).toBeNull()
    expect(parsePartyListPage({ ...valid, items: [valid.items[0], valid.items[0]] }, 0)).toBeNull()
    expect(parsePartyListPage({ data: valid.items, total: 2, page: 0, size: 25 }, 0)).toBeNull()
  })

  it('loads every reported page, shows progress and never duplicates a party', async () => {
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      requests.push(url)
      return url.includes('page=1')
        ? json({ items: [item(ids[0], 'Ada'), item(ids[1], 'Grace')], total: 27, page: 1, size: 25 })
        : json({ items: [item(ids[0], 'Ada')], total: 27, page: 0, size: 25 })
    }))

    mount()
    expect(await screen.findByText(/Loaded 1 of 27|Načteno 1 z 27/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Load more parties from the list|Načíst další subjekty ze seznamu/ }))

    expect(await screen.findByText(/Loaded 2 of 27|Načteno 2 z 27/)).toBeInTheDocument()
    expect(screen.getAllByText('Ada')).toHaveLength(1)
    expect(screen.getByText('Grace')).toBeInTheDocument()
    expect(requests[0]).toMatch(/[?&]page=0/)
    expect(requests[0]).toMatch(/[?&]size=25/)
    expect(requests[1]).toMatch(/[?&]page=1/)
  })

  it('does not present an unavailable or malformed endpoint as an empty registry', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'Unknown service: party-service' }, 404)))
    const view = mount()
    expect(await screen.findByText(/not deployed|není v tomto prostředí nasazená/)).toBeInTheDocument()
    expect(screen.queryByText(/No parties found|Žádné subjekty nenalezeny/)).not.toBeInTheDocument()

    view.unmount()
    vi.stubGlobal('fetch', vi.fn(async () => json({ items: 'not-an-array', total: 0, page: 0, size: 25 })))
    mount()
    await waitFor(() => expect(screen.getByText(/Failed to load: Parties|Načtení selhalo: Subjekty/)).toBeInTheDocument())
    expect(screen.queryByText(/No parties found|Žádné subjekty nenalezeny/)).not.toBeInTheDocument()
  })

  it('purges list evidence when authorization is lost', async () => {
    let unauthorized = false
    vi.stubGlobal('fetch', vi.fn(async () => unauthorized
      ? json({ error: 'unauthorized' }, 401)
      : json({ items: [item(ids[0], 'Ada')], total: 1, page: 0, size: 25 })))
    mount()

    expect(await screen.findByText('Ada')).toBeInTheDocument()
    unauthorized = true
    fireEvent.click(screen.getByRole('button', { name: /Refresh parties|Obnovit subjekty/ }))

    await waitFor(() => expect(screen.queryByText('Ada')).not.toBeInTheDocument())
    expect(screen.getByText(/session has expired|relace vypršela/)).toBeInTheDocument()
  })
})
