// SPDX-License-Identifier: Apache-2.0

// lending-service serialises `currency` on loans and applications as the domain object
// `{ code, defaultFractionDigits }`, but as a plain string on its summaries. The console rendered
// the object as `[object Object]` and — because the headline total filters summary rows by
// `currency === ccy` with `ccy` taken from a loan — compared a string to an object, matched nothing
// and reported a principal of 0 against a multi-million book.

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import LendingPage from '@/app/lending/page'
import { currencyCode, formatMoney } from '@/lib/lending/money'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
}))

const json = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })

const CZK = { code: 'CZK', defaultFractionDigits: 2 }

const routes: Record<string, unknown> = {
  '/applications/recent': [
    { id: 'a1', partyId: 'p1', status: 'SUBMITTED', createdAt: new Date().toISOString(), requestedAmount: { amount: 30000, currency: CZK } },
  ],
  '/loans/active': [
    { id: 'l1', partyId: 'p2', status: 'ACTIVE', principal: { amount: 150000, currency: CZK } },
    { id: 'l2', partyId: 'p3', status: 'ACTIVE', principal: { amount: 250000, currency: CZK } },
  ],
  '/applications/summary': [
    { status: 'SUBMITTED', count: 1, oldestCreatedAt: new Date().toISOString(), requested: [{ currency: 'CZK', amount: 30000 }] },
  ],
  '/loans/summary': [
    { status: 'ACTIVE', count: 2, principal: [{ currency: 'CZK', amount: 400000 }] },
    { status: 'DELINQUENT', count: 1, principal: [{ currency: 'CZK', amount: 50000 }] },
    // Off the book: must count neither as active nor towards the lent total.
    { status: 'SETTLED', count: 5, principal: [{ currency: 'CZK', amount: 750000 }] },
    { status: 'WRITTEN_OFF', count: 1, principal: [{ currency: 'CZK', amount: 90000 }] },
  ],
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('lending console with object-shaped currency', () => {
  it('extracts the code from either wire shape', () => {
    expect(currencyCode('EUR')).toBe('EUR')
    expect(currencyCode(CZK)).toBe('CZK')
    expect(currencyCode({})).toBeUndefined()
    expect(currencyCode(undefined)).toBeUndefined()
  })

  it('renders currency codes and sums the whole-book principal', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const key = Object.keys(routes).find(k => String(url).split('?')[0].endsWith(k))
      return json(key ? routes[key] : [])
    }))
    render(<LanguageProvider><LendingPage /></LanguageProvider>)

    await waitFor(() => expect(document.body.textContent).toMatch(/originally lent CZK\s450,000/))
    expect(document.body.textContent).toMatch(/requested CZK\s30,000/)
    const cells = Array.from(document.querySelectorAll('td')).map(td => td.textContent?.replace(/\s/g, ' '))
    expect(cells).toContain('CZK 30,000')
    expect(document.body.textContent).not.toContain('[object Object]')
    // Active = on the book and not in trouble: the 2 ACTIVE, not the SETTLED 5 nor the troubled ones.
    expect(Array.from(document.querySelectorAll('.stat-value')).map(n => n.textContent)[0]).toBe('2')

    fireEvent.click(screen.getByLabelText('Show portfolio'))
    await waitFor(() => expect(Array.from(document.querySelectorAll('td')).map(td => td.textContent?.replace(/\s/g, ' '))).toContain('Active'))
    expect(Array.from(document.querySelectorAll('td')).map(td => td.textContent?.replace(/\s/g, ' '))).not.toContain('ACTIVE')
  })

  it('renders the credit-risk currency form so both pages read alike', () => {
    expect(formatMoney(6600000, 'CZK', 'cs-CZ').replace(/\s/g, ' ')).toBe('6 600 000 Kč')
    expect(formatMoney(150000, undefined, 'cs-CZ').replace(/\s/g, ' ')).toBe('150 000')
  })
})
