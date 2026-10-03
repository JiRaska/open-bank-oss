// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury daily position: ACTUAL vs PROJECTED basis, the empty state instead of silent zeros,
// and the date shortcuts (Dnes / Zítra / +7 / +30 dní) counted from the bank's day.

import React, { Suspense } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { addDays, basisOf, isEmptyPosition, longDate } from '@/components/treasury/positions'
import { bankToday } from '@/components/balance-sheet/model'

const jwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'Petr', roles: ['ROLE_TREASURY_APPROVER'], accessToken: jwt({ preferred_username: 'petr', sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/treasury/positions',
  useSearchParams: () => new URLSearchParams(),
}))

import TreasuryPositionsPage from '@/app/treasury/positions/page'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const row = (currency: string, placed = 0, borrowed = 0, atCnb = 0, dealCount = 0) =>
  ({ currency, placed, borrowed, atCnb, net: placed + atCnb - borrowed, dealCount })

let calls: string[] = []
let router: (url: string) => Response

beforeEach(() => {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (u: string) => { calls.push(String(u)); return router(String(u)) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

const renderPage = async () => {
  await act(async () => { render(<LanguageProvider><Suspense fallback={null}><TreasuryPositionsPage /></Suspense></LanguageProvider>) })
}
const asOfOf = (url: string) => new URL(url, 'http://x').searchParams.get('asOf')

describe('treasury daily position — model', () => {
  it('adds calendar days across a month end without a zone shift', () => {
    expect(addDays('2026-09-30', 1)).toBe('2026-10-01')
    expect(addDays('2026-10-01', 30)).toBe('2026-10-31')
  })
  it('takes the service basis, else derives PROJECTED only after today', () => {
    const base = { asOf: '2026-10-02', positions: [] }
    expect(basisOf({ ...base, basis: 'ACTUAL' }, '2026-10-01')).toBe('ACTUAL')
    expect(basisOf(base, '2026-10-01')).toBe('PROJECTED')
    expect(basisOf({ ...base, asOf: '2026-10-01' }, '2026-10-01')).toBe('ACTUAL')
  })
  it('an all-zero answer is empty, a counted deal is not', () => {
    expect(isEmptyPosition({ asOf: '2026-10-01', positions: [row('CZK'), row('EUR')] })).toBe(true)
    expect(isEmptyPosition({ asOf: '2026-10-01', positions: [row('CZK', 0, 0, 10_000_000, 1), row('EUR')] })).toBe(false)
  })
  it('spells the chosen day in Czech regardless of the browser locale', () => {
    expect(longDate('2026-10-01', 'cs')).toMatch(/čtvrtek 1\. října 2026/)
  })
})

describe('treasury daily position — page', () => {
  it('shows a clear empty state instead of silent zeros', async () => {
    router = u => json({ asOf: asOfOf(u), today: asOfOf(u), basis: 'ACTUAL', countedStates: ['MATURED', 'SETTLED'], positions: [row('CZK'), row('EUR')] })
    await renderPage()
    expect(await screen.findByText(/No deals are outstanding on this day|K tomuto dni nejsou otevřené žádné obchody/)).toBeTruthy()
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.getByText(/^(Actual|Skutečnost)$/)).toBeTruthy()
  })

  it('labels a future date as a projection and shows the currency on every row', async () => {
    router = u => json({
      asOf: asOfOf(u), today: bankToday(), basis: 'PROJECTED', countedStates: ['BOOKED', 'CONFIRMED', 'MATURED', 'SETTLED'],
      positions: [row('CZK', 250000, 0, 0, 1), row('EUR', 0, 40000, 0, 1)],
    })
    await renderPage()
    expect(await screen.findByText(/^(Projection|Projekce)$/)).toBeTruthy()
    expect(screen.getByText(/concluded deals only, no new ones|jen sjednané obchody, bez nových/)).toBeTruthy()
    expect(screen.getByRole('table').getAttribute('data-basis')).toBe('PROJECTED')
    expect(screen.getByRole('rowheader', { name: 'CZK' })).toBeTruthy()
    expect(screen.getByRole('rowheader', { name: 'EUR' })).toBeTruthy()
    expect(screen.getAllByText(/250\s000,00\sKč/)).toHaveLength(2) // placed + net
  })

  it('the shortcuts ask for today, tomorrow, +7 and +30 days from the bank day', async () => {
    router = u => json({ asOf: asOfOf(u), positions: [row('CZK'), row('EUR')] })
    await renderPage()
    const today = bankToday()
    expect(asOfOf(calls[0])).toBe(today)
    for (const [name, days] of [[/^(Tomorrow|Zítra)$/, 1], [/^\+7 (days|dní)$/, 7], [/^\+30 (days|dní)$/, 30], [/^(Today|Dnes)$/, 0]] as const) {
      await act(async () => { fireEvent.click(screen.getByRole('button', { name })) })
      expect(asOfOf(calls[calls.length - 1])).toBe(addDays(today, days))
    }
  })
})
