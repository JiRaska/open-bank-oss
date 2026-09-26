// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import MerchantsPage from '@/app/merchants/page'

vi.mock('@/components/auth/AuthGuard', () => ({ Can: ({ children }: { children: React.ReactNode }) => <>{children}</> }))

const billa = {
  descriptorKey: 'BILLA',
  cleanName: 'Billa',
  city: 'Praha',
  country: 'CZ',
  lat: 50.0834,
  lon: 14.4238,
  geoPrecision: 'CITY',
}

const brno = {
  descriptorKey: 'BILLA',
  cityToken: 'BRNO',
  lat: 49.1951,
  lon: 16.6068,
  city: 'Brno',
  country: 'CZ',
  precision: 'CITY',
  terminalId: null,
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

function stubFetch(locations: unknown[] = [], onWrite: () => Response = () => json({}, 201)) {
  const calls: Array<{ url: string; method: string; body?: string }> = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    calls.push({ url: String(url), method: init?.method ?? 'GET', body: init?.body as string | undefined })
    if (init?.method && init.method !== 'GET') return Promise.resolve(onWrite())
    if (String(url).includes('/locations')) return Promise.resolve(json(locations))
    if (String(url).includes('/unmatched')) return Promise.resolve(json([]))
    return Promise.resolve(json({ data: [billa], total: 1 }))
  }))
  return calls
}

const renderPage = () => render(React.createElement(LanguageProvider, null, React.createElement(MerchantsPage)))

const openLocations = async () => {
  renderPage()
  await waitFor(() => expect(screen.getByText('Billa')).toBeInTheDocument())
  fireEvent.click(screen.getByLabelText('Locations for BILLA'))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('merchant locations', () => {
  /**
   * The catalogue pin is for the BRAND. An operator reading the row cannot tell that from a pair of
   * coordinates, and telling them is the whole point of the precision field existing.
   */
  it('labels the catalogue pin with its precision', async () => {
    stubFetch()

    renderPage()

    await waitFor(() => expect(screen.getByText('Billa')).toBeInTheDocument())
    expect(screen.getAllByText('CITY').length).toBeGreaterThan(0)
  })

  it('loads a merchant’s locations on demand, not with the page', async () => {
    const calls = stubFetch([brno])

    await openLocations()

    await waitFor(() => expect(screen.getByText('BRNO')).toBeInTheDocument())
    expect(calls.filter(c => c.url.includes('/locations'))).toHaveLength(1)
  })

  /**
   * A merchant with no locations must say what that costs, not just show an empty table: without
   * them every transaction resolves to the one catalogue pin, which for a chain is the brand.
   */
  it('says what a merchant with no locations means', async () => {
    stubFetch([])

    await openLocations()

    await waitFor(() => expect(screen.getByText(/every transaction resolves to the single catalogue pin/)).toBeInTheDocument())
  })

  /**
   * EXACT is refused by the API and by a database constraint without a terminal id. The option is
   * disabled here so an operator sees the rule rather than decoding a 400 — the server stays the
   * enforcement point, this is only the explanation.
   */
  it('offers EXACT only once a terminal id is given', async () => {
    stubFetch([])

    await openLocations()
    await waitFor(() => expect(screen.getByText('Add a location')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Add a location'))

    const exact = await screen.findByRole('option', { name: 'EXACT' }) as HTMLOptionElement
    expect(exact.disabled).toBe(true)

    fireEvent.change(screen.getByLabelText('Terminal id'), { target: { value: 'T-00042' } })
    expect((screen.getByRole('option', { name: 'EXACT' }) as HTMLOptionElement).disabled).toBe(false)
  })

  it('writes a location under the town token, keyed the way the read path keys it', async () => {
    const calls = stubFetch([])

    await openLocations()
    await waitFor(() => expect(screen.getByText('Add a location')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Add a location'))
    fireEvent.change(screen.getByLabelText('Town token'), { target: { value: 'BRNO' } })
    fireEvent.change(screen.getByLabelText('Latitude'), { target: { value: '49.1951' } })
    fireEvent.change(screen.getByLabelText('Longitude'), { target: { value: '16.6068' } })
    fireEvent.click(screen.getByText('Save the location'))

    await waitFor(() => expect(calls.some(c => c.method === 'PUT')).toBe(true))
    const put = calls.find(c => c.method === 'PUT')!
    expect(put.url).toContain('/api/v1/merchants/BILLA/locations/BRNO')
    expect(JSON.parse(put.body!)).toMatchObject({ lat: 49.1951, lon: 16.6068, precision: 'CITY' })
  })

  /** Coordinates are not optional for a location — an incomplete row cannot be saved at all. */
  it('will not save a location without coordinates', async () => {
    const calls = stubFetch([])

    await openLocations()
    await waitFor(() => expect(screen.getByText('Add a location')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Add a location'))
    fireEvent.change(screen.getByLabelText('Town token'), { target: { value: 'BRNO' } })

    expect((screen.getByText('Save the location') as HTMLButtonElement).disabled).toBe(true)
    expect(calls.some(c => c.method === 'PUT')).toBe(false)
  })

  it('surfaces the service’s reason when a location write is refused', async () => {
    stubFetch([], () => json({ message: 'precision EXACT requires a terminalId' }, 400))

    await openLocations()
    await waitFor(() => expect(screen.getByText('Add a location')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Add a location'))
    fireEvent.change(screen.getByLabelText('Town token'), { target: { value: 'BRNO' } })
    fireEvent.change(screen.getByLabelText('Latitude'), { target: { value: '49.1' } })
    fireEvent.change(screen.getByLabelText('Longitude'), { target: { value: '16.6' } })
    fireEvent.click(screen.getByText('Save the location'))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('precision EXACT requires a terminalId'))
  })
})
