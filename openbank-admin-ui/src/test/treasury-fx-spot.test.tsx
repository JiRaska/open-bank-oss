// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// FX spot deals in the admin console (#10896, stacked on treasury-service PR #11041): the booking
// form's buy/sell currency selects, the client-side "exactly one CZK" and value-date courtesy
// checks, the CZK preview (never booked), and FX rendering on the blotter and detail page. The
// server (Deal.kt) is the actual control throughout — this only proves the console does not send
// or render nonsense.

import React, { Suspense } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const session = vi.hoisted(() => ({ roles: ['ROLE_TREASURY_DEALER'] as string[], username: 'dana.dealer' }))
const jwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'Dana', roles: session.roles, accessToken: jwt({ preferred_username: session.username, sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))

import NewTreasuryDealPage from '@/app/treasury/deals/new/page'
import TreasuryDealsPage from '@/app/treasury/deals/page'
import TreasuryDealDetailPage from '@/app/treasury/deals/[id]/page'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const COUNTERPARTIES = [
  { counterpartyId: 'SIM-A', name: 'Sim Bank A', kind: 'BANK', synthetic: true, currency: 'CZK', limit: 5000000, exposure: 1000000, headroom: 4000000 },
  { counterpartyId: 'SIM-A', name: 'Sim Bank A', kind: 'BANK', synthetic: true, currency: 'EUR', limit: 200000, exposure: 0, headroom: 200000 },
]

const fxDeal = (dealId: string, overrides: Record<string, unknown> = {}) => ({
  dealId, product: 'FX_SPOT', counterpartyId: 'SIM-A', currency: 'EUR', principal: 1000, rate: 25.10,
  dayCount: 'ACT/360', days: 0, interest: 0, tradeDate: '2026-09-25', valueDate: '2026-09-29', maturityDate: '2026-09-29',
  state: 'DRAFT', createdBy: 'dana.dealer', createdByType: 'HUMAN', submittedBy: null, approvedBy: null, rationale: null,
  limitCheck: null,
  fx: {
    side: 'BUY', buyCurrency: 'EUR', buyAmount: 1000, sellCurrency: 'CZK', sellAmount: 25100,
    dealRate: 25.10, midRate: null, rateFlag: null,
  },
  createdAt: '2026-09-25T08:00:00Z', updatedAt: '2026-09-25T08:00:00Z',
  history: [{ from: null, to: 'DRAFT', actor: 'dana.dealer', actorType: 'HUMAN', at: '2026-09-25T08:00:00Z', note: null }],
  journals: [],
  ...overrides,
})

const renderPage = async (node: React.ReactNode) => {
  await act(async () => { render(<LanguageProvider><Suspense fallback={null}>{node}</Suspense></LanguageProvider>) })
}

let calls: { url: string; init?: RequestInit }[] = []
let router: (url: string, init?: RequestInit) => Response

beforeEach(() => {
  calls = []
  session.roles = ['ROLE_TREASURY_DEALER']
  session.username = 'dana.dealer'
  vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => { calls.push({ url: String(u), init }); return router(String(u), init) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

const selectFxProduct = async () => {
  await screen.findAllByRole('option', { name: /Sim Bank A/ })
  fireEvent.change(screen.getByLabelText(/^(Produkt|Product)$/), { target: { value: 'FX_SPOT' } })
  fireEvent.change(screen.getByLabelText(/^(Protistrana|Counterparty)$/), { target: { value: 'SIM-A' } })
}

describe('new deal — FX spot form', () => {
  beforeEach(() => {
    router = url => (url.includes('/counterparties') ? json(COUNTERPARTIES) : json({}, 404))
  })

  it('shows buy/sell currency selects and a foreign-amount / rate labelling instead of principal/rate', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await selectFxProduct()
    expect(screen.getByLabelText(/^(Kupovaná měna|Buy currency)$/)).toBeTruthy()
    expect(screen.getByLabelText(/^(Prodávaná měna|Sell currency)$/)).toBeTruthy()
    expect(screen.getByLabelText(/^(Cizoměnová částka|Foreign amount)$/)).toBeTruthy()
    // No standalone currency select and no maturity date for FX_SPOT.
    expect(screen.queryByLabelText(/^(Měna|Currency)$/)).toBeNull()
    expect(screen.queryByLabelText(/^(Datum splatnosti|Maturity date)$/)).toBeNull()
  })

  it('refuses when both legs are CZK, and when neither leg is CZK', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await selectFxProduct()
    fireEvent.change(screen.getByLabelText(/^(Kupovaná měna|Buy currency)$/), { target: { value: 'CZK' } })
    fireEvent.change(screen.getByLabelText(/^(Prodávaná měna|Sell currency)$/), { target: { value: 'CZK' } })
    fireEvent.change(screen.getByLabelText(/^(Cizoměnová částka|Foreign amount)$/), { target: { value: '1000' } })
    fireEvent.change(screen.getByLabelText(/CZK per unit of foreign currency|CZK za jednotku cizí měny/), { target: { value: '25.10' } })
    expect(screen.getByRole('button', { name: /Create draft|Vytvořit koncept/ })).toBeDisabled()
    expect(screen.getByRole('alert').textContent).toMatch(/exactly one|Právě jedna/i)

    fireEvent.change(screen.getByLabelText(/^(Prodávaná měna|Sell currency)$/), { target: { value: 'EUR' } })
    // both CZK/EUR pair with buy=CZK, sell=EUR is still invalid the other way (neither distinguishes) —
    // switch buy to EUR too so neither leg is CZK
    fireEvent.change(screen.getByLabelText(/^(Kupovaná měna|Buy currency)$/), { target: { value: 'EUR' } })
    expect(screen.getByRole('button', { name: /Create draft|Vytvořit koncept/ })).toBeDisabled()
    expect(screen.getByRole('alert').textContent).toMatch(/exactly one|Právě jedna/i)
  })

  it('accepts a valid pair (one CZK leg) and enables the create button', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await selectFxProduct()
    fireEvent.change(screen.getByLabelText(/^(Kupovaná měna|Buy currency)$/), { target: { value: 'EUR' } })
    fireEvent.change(screen.getByLabelText(/^(Prodávaná měna|Sell currency)$/), { target: { value: 'CZK' } })
    fireEvent.change(screen.getByLabelText(/^(Cizoměnová částka|Foreign amount)$/), { target: { value: '1000' } })
    fireEvent.change(screen.getByLabelText(/CZK per unit of foreign currency|CZK za jednotku cizí měny/), { target: { value: '25.10' } })
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('button', { name: /Create draft|Vytvořit koncept/ })).not.toBeDisabled()
  })

  it('previews the CZK counter amount as amount x rate, rounded to 2dp, labelled a preview', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await selectFxProduct()
    fireEvent.change(screen.getByLabelText(/^(Kupovaná měna|Buy currency)$/), { target: { value: 'EUR' } })
    fireEvent.change(screen.getByLabelText(/^(Prodávaná měna|Sell currency)$/), { target: { value: 'CZK' } })
    fireEvent.change(screen.getByLabelText(/^(Cizoměnová částka|Foreign amount)$/), { target: { value: '1000' } })
    fireEvent.change(screen.getByLabelText(/CZK per unit of foreign currency|CZK za jednotku cizí měny/), { target: { value: '25.105' } })
    const preview = await screen.findByTestId('fx-preview')
    expect(preview.textContent).toMatch(/preview|náhled/i)
    expect(preview.textContent).toMatch(/25[\s,. ]?105(\.00)?/)
    expect(preview.textContent).toMatch(/not booked|nezaúčtováno/i)
  })

  it('sends buyCurrency/sellCurrency and the foreign amount as principal, omitting maturityDate and currency', async () => {
    const created = fxDeal('fx-1')
    router = (url, init) => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      if (url.endsWith('/api/v1/treasury/deals') && init?.method === 'POST') return json(created, 201)
      return json({}, 404)
    }
    await renderPage(<NewTreasuryDealPage />)
    await selectFxProduct()
    fireEvent.change(screen.getByLabelText(/^(Kupovaná měna|Buy currency)$/), { target: { value: 'EUR' } })
    fireEvent.change(screen.getByLabelText(/^(Prodávaná měna|Sell currency)$/), { target: { value: 'CZK' } })
    fireEvent.change(screen.getByLabelText(/^(Cizoměnová částka|Foreign amount)$/), { target: { value: '1000' } })
    fireEvent.change(screen.getByLabelText(/CZK per unit of foreign currency|CZK za jednotku cizí měny/), { target: { value: '25.10' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Create draft|Vytvořit koncept/ })) })
    const post = calls.find(c => c.init?.method === 'POST')!
    const body = JSON.parse(String(post.init!.body))
    expect(body).toMatchObject({ product: 'FX_SPOT', counterpartyId: 'SIM-A', buyCurrency: 'EUR', sellCurrency: 'CZK', principal: 1000, rate: 25.10 })
    expect(body.currency).toBeUndefined()
    expect(body.maturityDate).toBeUndefined()
  })

  it('leaves valueDate out of the payload when left empty (server defaults to T+2)', async () => {
    const created = fxDeal('fx-2')
    router = (url, init) => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      if (url.endsWith('/api/v1/treasury/deals') && init?.method === 'POST') return json(created, 201)
      return json({}, 404)
    }
    await renderPage(<NewTreasuryDealPage />)
    await selectFxProduct()
    fireEvent.change(screen.getByLabelText(/^(Kupovaná měna|Buy currency)$/), { target: { value: 'EUR' } })
    fireEvent.change(screen.getByLabelText(/^(Prodávaná měna|Sell currency)$/), { target: { value: 'CZK' } })
    fireEvent.change(screen.getByLabelText(/^(Cizoměnová částka|Foreign amount)$/), { target: { value: '1000' } })
    fireEvent.change(screen.getByLabelText(/CZK per unit of foreign currency|CZK za jednotku cizí měny/), { target: { value: '25.10' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Create draft|Vytvořit koncept/ })) })
    const post = calls.find(c => c.init?.method === 'POST')!
    expect(JSON.parse(String(post.init!.body)).valueDate).toBeUndefined()
  })

  it('refuses a value date later than T+2 business days', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await selectFxProduct()
    fireEvent.change(screen.getByLabelText(/^(Kupovaná měna|Buy currency)$/), { target: { value: 'EUR' } })
    fireEvent.change(screen.getByLabelText(/^(Prodávaná měna|Sell currency)$/), { target: { value: 'CZK' } })
    fireEvent.change(screen.getByLabelText(/^(Cizoměnová částka|Foreign amount)$/), { target: { value: '1000' } })
    fireEvent.change(screen.getByLabelText(/CZK per unit of foreign currency|CZK za jednotku cizí měny/), { target: { value: '25.10' } })
    const farFuture = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
    fireEvent.change(screen.getByLabelText(/^(Datum valuty|Value date)$/), { target: { value: farFuture } })
    expect(screen.getByRole('button', { name: /Create draft|Vytvořit koncept/ })).toBeDisabled()
  })
})

describe('FX spot rendering', () => {
  it('renders the FX side, currency pair and CZK counter amount on the blotter', async () => {
    router = url => (url.includes('/counterparties') ? json(COUNTERPARTIES) : json([fxDeal('fx-1')]))
    await renderPage(<TreasuryDealsPage />)
    await screen.findByText('dana.dealer')
    expect(screen.getByText(/BUY EUR\/CZK/)).toBeTruthy()
    expect(screen.getByText(/25[\s,. ]?100/)).toBeTruthy()
  })

  it('renders FX terms on the deal detail page', async () => {
    router = url => (url.includes('/counterparties') ? json(COUNTERPARTIES) : json(fxDeal('fx-1')))
    await renderPage(<TreasuryDealDetailPage params={Promise.resolve({ id: 'fx-1' })} />)
    const fxCard = await screen.findByTestId('fx-terms')
    expect(fxCard.textContent).toContain('BUY')
    expect(fxCard.textContent).toContain('EUR/CZK')
    expect(fxCard.textContent).toMatch(/1[\s,. ]?000\.00 EUR/)
    expect(fxCard.textContent).toMatch(/25[\s,. ]?100\.00 CZK/)
  })

  it('renders a future, unrecognised product gracefully instead of crashing (x-extensible-enum)', async () => {
    const unknownDeal = fxDeal('future-1', { product: 'FX_FORWARD', fx: null })
    router = url => (url.includes('/counterparties') ? json(COUNTERPARTIES) : json([unknownDeal]))
    await renderPage(<TreasuryDealsPage />)
    await screen.findByText('dana.dealer')
    expect(screen.getByText('FX_FORWARD')).toBeTruthy()
  })
})
