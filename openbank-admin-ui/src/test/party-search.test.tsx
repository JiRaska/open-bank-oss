// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// PartySearch exists because Customer 360 and Consents were keyed by party UUID and exposed that key
// as their search box — unusable without a second tab open on the Parties page. It resolves a name
// to an id against party-service (ADR-0055).
//
// What this asserts is the behaviour that is easy to regress and invisible when it breaks:
//   1. A pasted UUID must NOT be sent to the trigram endpoint. It is already the answer, so it goes
//      straight to onSelect. If this regressed, a valid id would return an empty result list and the
//      page would look broken for the one input that is guaranteed correct.
//   2. Zero matches must NOT render as an unavailable data source. That conflation is the exact
//      defect this component was written to remove, and it is copy — nothing else would catch it.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import React from 'react'
import { render, cleanup, screen, fireEvent, waitFor } from '@testing-library/react'
import { PartySearch, PARTY_UUID_RE, partyDisplayName } from '@/components/party/PartySearch'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const UUID = '24977cca-20b2-4877-80d1-403b40181a89'

function mount(onSelect: (p: { id: string }) => void) {
  return render(
    <LanguageProvider>
      <PartySearch onSelect={onSelect} />
    </LanguageProvider>,
  )
}

function type(value: string) {
  const input = screen.getByRole('textbox')
  fireEvent.change(input, { target: { value } })
  return input
}

beforeEach(() => {
  vi.unstubAllGlobals()
})
afterEach(() => cleanup())

describe('PartySearch', () => {
  it('passes a pasted party UUID straight through, without calling the search endpoint', async () => {
    const fetchSpy = vi.fn(async () => ({ ok: true, json: async () => ({ data: [] }) }) as unknown as Response)
    vi.stubGlobal('fetch', fetchSpy)
    const onSelect = vi.fn()

    mount(onSelect)
    fireEvent.keyDown(type(UUID), { key: 'Enter' })

    await waitFor(() => expect(onSelect).toHaveBeenCalledWith({ id: UUID }))
    expect(fetchSpy).not.toHaveBeenCalled() // the id IS the answer — no trigram round-trip
  })

  it('cancels a superseded name lookup and clears busy state for a pasted UUID', async () => {
    let firstSignal: AbortSignal | undefined
    const fetchSpy = vi.fn((_url: string, init?: RequestInit) => {
      firstSignal = init?.signal ?? undefined
      return new Promise<Response>(() => {})
    })
    vi.stubGlobal('fetch', fetchSpy)
    const onSelect = vi.fn()

    mount(onSelect)
    fireEvent.keyDown(type('Novák'), { key: 'Enter' })
    expect(await screen.findByRole('status')).toHaveTextContent(/Hledám|Searching/)

    fireEvent.keyDown(type(UUID), { key: 'Enter' })

    await waitFor(() => expect(onSelect).toHaveBeenCalledWith({ id: UUID }))
    expect(firstSignal?.aborted).toBe(true)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })

  it('cancels an in-flight lookup when its workflow unmounts', async () => {
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => {
      signal = init?.signal ?? undefined
      return new Promise<Response>(() => {})
    }))

    const view = mount(vi.fn())
    fireEvent.keyDown(type('Novák'), { key: 'Enter' })
    expect(await screen.findByRole('status')).toBeVisible()

    view.unmount()
    expect(signal?.aborted).toBe(true)
  })

  it('searches party-service by name and does not select until a row is chosen', async () => {
    const hit = { id: UUID, legalName: 'Jan Novák', email: 'jan@example.test', status: 'ACTIVE' }
    const fetchSpy = vi.fn(async () => ({ ok: true, json: async () => ({ data: [hit] }) }) as unknown as Response)
    vi.stubGlobal('fetch', fetchSpy)
    const onSelect = vi.fn()

    mount(onSelect)
    fireEvent.keyDown(type('Novák'), { key: 'Enter' })

    await waitFor(() => expect(screen.getByText('Jan Novák')).toBeTruthy())
    const url = String(fetchSpy.mock.calls[0][0])
    expect(url).toContain('/api/svc/party-service/api/v1/parties/search')
    expect(url).toContain(`q=${encodeURIComponent('Novák')}`)
    expect(onSelect).not.toHaveBeenCalled() // a result list is not a selection

    fireEvent.click(screen.getByRole('button', { name: /Vybrat|Select/ }))
    expect(onSelect).toHaveBeenCalledWith(hit)
  })

  it('exposes truthful search and selection state', async () => {
    const hit = { id: UUID, legalName: 'Jan Novák', status: 'ACTIVE' }
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ data: [hit] }) }) as unknown as Response))

    mount(vi.fn())
    const input = type('Novák')
    expect(input).toHaveAttribute('aria-controls', 'party-search-results')
    fireEvent.keyDown(input, { key: 'Enter' })

    const select = await screen.findByRole('button', { name: /Vybrat Jan Novák|Select Jan Novák/ })
    expect(select).toHaveAttribute('type', 'button')
    expect(select).toHaveAttribute('aria-pressed', 'false')
  })

  it('renders zero matches as a search result, never as an unavailable data source', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ data: [] }) }) as unknown as Response))

    mount(vi.fn())
    fireEvent.keyDown(type('nobody'), { key: 'Enter' })

    await waitFor(() => expect(screen.getByText(/Žádná party neodpovídá|No party matches/)).toBeTruthy())
    // The copy that made a working page look broken. It must not appear for an empty result set.
    expect(screen.queryByText(/neobsahuje žádné záznamy|does not contain any records/)).toBeNull()
  })

  it('does distinguish an unreachable party-service from zero matches', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('ECONNREFUSED') }))

    mount(vi.fn())
    fireEvent.keyDown(type('Novák'), { key: 'Enter' })

    // DataUnavailable's unreachable copy, not the "no party matches" one.
    await waitFor(() => expect(screen.queryByText(/Žádná party neodpovídá|No party matches/)).toBeNull())
  })

  it('accepts only a well-formed UUID as the direct path', () => {
    expect(PARTY_UUID_RE.test(UUID)).toBe(true)
    expect(PARTY_UUID_RE.test(UUID.toUpperCase())).toBe(true)
    expect(PARTY_UUID_RE.test('Novák')).toBe(false)
    expect(PARTY_UUID_RE.test(UUID.slice(0, -1))).toBe(false) // too short must still be a name search
  })

  it('falls back through legalName, tradingName, then id for the display name', () => {
    expect(partyDisplayName({ id: UUID, legalName: 'A', tradingName: 'B' })).toBe('A')
    expect(partyDisplayName({ id: UUID, legalName: null, tradingName: 'B' })).toBe('B')
    expect(partyDisplayName({ id: UUID })).toBe(UUID) // never renders an empty cell
  })
})
