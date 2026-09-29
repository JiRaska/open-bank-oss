// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// #10896: product CNB_LOMBARD (overnight borrowing from the ČNB marginal lending facility) — the
// booking form fixes CZK/ČNB and a computed next-business-day maturity, requires a dealer-entered
// rate > 0, and the deal list/detail label it "ČNB lombardní úvěr" with a collateral-not-modelled
// note on detail (the pledge is not modelled, matching the treasury-service domain and threat model).
import React, { Suspense } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { eligibleCounterparties, isAssetProduct, nextBusinessDay, productLabel } from '@/components/treasury/model'
import { dealSchema, productSchema } from '@/components/treasury/contracts'

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
  { counterpartyId: 'CNB', name: 'Česká národní banka', kind: 'CENTRAL_BANK', synthetic: false, currency: 'CZK', limit: 1e12, exposure: 0, headroom: 1e12 },
  { counterpartyId: 'SIM-A', name: 'Sim Bank A', kind: 'BANK', synthetic: true, currency: 'CZK', limit: 5000000, exposure: 1000000, headroom: 4000000 },
]

const lombardDeal = (overrides: Record<string, unknown> = {}) => ({
  dealId: 'lomb-1', product: 'CNB_LOMBARD', counterpartyId: 'CNB', currency: 'CZK', principal: 2500000, rate: 5.75,
  dayCount: 'ACT/360', days: 1, interest: 399.31, tradeDate: '2026-09-25', valueDate: '2026-09-25', maturityDate: '2026-09-28',
  state: 'PENDING_APPROVAL', createdBy: 'dana.dealer', createdByType: 'HUMAN', submittedBy: 'dana.dealer', approvedBy: null,
  rationale: null, limitCheck: null, limitOverride: null,
  createdAt: '2026-09-25T08:00:00Z', updatedAt: '2026-09-25T08:01:00Z',
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

describe('model — CNB_LOMBARD constraints', () => {
  it('reads a future response product without offering it for draft creation', () => {
    expect(dealSchema.parse(lombardDeal({ product: 'FUTURE_PRODUCT' })).product).toBe('FUTURE_PRODUCT')
    expect(productLabel('FUTURE_PRODUCT', (cs) => cs)).toBe('FUTURE_PRODUCT')
    expect(productSchema.safeParse('FUTURE_PRODUCT').success).toBe(false)
  })

  it('is a liability like MM_BORROWING: consumes no counterparty limit', () => {
    expect(isAssetProduct('CNB_LOMBARD')).toBe(false)
  })

  it('is eligible only for the ČNB counterparty, like the deposit facility', () => {
    expect(eligibleCounterparties(COUNTERPARTIES as never, 'CNB_LOMBARD').map(c => c.counterpartyId)).toEqual(['CNB'])
  })

  it('labels the product "ČNB lombardní úvěr" / "ČNB lombard borrowing"', () => {
    expect(productLabel('CNB_LOMBARD', (cs) => cs)).toBe('ČNB lombardní úvěr')
    expect(productLabel('CNB_LOMBARD', (_cs, en) => en)).toBe('ČNB lombard borrowing')
  })

  it('computes the next business day, weekend-aware — Friday matures Monday', () => {
    expect(nextBusinessDay('2026-09-28')).toBe('2026-09-29') // Mon -> Tue
    expect(nextBusinessDay('2026-09-25')).toBe('2026-09-28') // Fri -> Mon (2026-09-25 is a Friday)
    expect(nextBusinessDay('2026-09-26')).toBe('2026-09-28') // Sat -> Mon
    expect(nextBusinessDay('2026-09-27')).toBe('2026-09-28') // Sun -> Mon
    expect(nextBusinessDay('')).toBe('')
    expect(nextBusinessDay('not-a-date')).toBe('')
  })
})

describe('new deal form — CNB_LOMBARD', () => {
  beforeEach(() => {
    router = url => (url.includes('/counterparties') ? json(COUNTERPARTIES) : json({}, 404))
  })

  it('fixes currency CZK and counterparty CNB (read-only), hides the maturity input, and previews the computed maturity', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    fireEvent.change(screen.getByLabelText(/^(Produkt|Product)$/), { target: { value: 'CNB_LOMBARD' } })
    fireEvent.change(screen.getByLabelText(/^(Datum valuty|Value date)$/), { target: { value: '2026-09-25' } })

    const cps = screen.getByLabelText(/^(Protistrana|Counterparty)$/) as HTMLSelectElement
    expect([...cps.options].map(o => o.value)).toEqual(['CNB'])
    expect(cps.value).toBe('CNB')
    expect(cps.disabled).toBe(true)
    const ccy = screen.getByLabelText(/^(Měna|Currency)$/) as HTMLSelectElement
    expect(ccy.value).toBe('CZK')
    expect(ccy.disabled).toBe(true)
    expect(screen.queryByLabelText(/^(Datum splatnosti|Maturity date)$/)).toBeNull()

    const preview = screen.getByTestId('facility-maturity-preview')
    expect(preview.textContent).toMatch(/2026-09-28/) // Friday value date -> Monday maturity
  })

  it('labels the rate field "Lombardní sazba ČNB (%)" and requires a strictly positive rate', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    fireEvent.change(screen.getByLabelText(/^(Produkt|Product)$/), { target: { value: 'CNB_LOMBARD' } })
    expect(screen.getByText(/Lombardní sazba ČNB \(%\)|CNB lombard rate \(%\)/)).toBeTruthy()

    fireEvent.change(screen.getByLabelText(/^(Jistina|Principal)$/), { target: { value: '2500000' } })
    fireEvent.change(screen.getByLabelText(/Lombardní sazba ČNB \(%\)|CNB lombard rate \(%\)/), { target: { value: '0' } })
    expect(screen.getByRole('button', { name: /Create draft|Vytvořit koncept/ })).toBeDisabled()

    fireEvent.change(screen.getByLabelText(/Lombardní sazba ČNB \(%\)|CNB lombard rate \(%\)/), { target: { value: '5.75' } })
    expect(screen.getByRole('button', { name: /Create draft|Vytvořit koncept/ })).toBeEnabled()
  })

  it('posts CNB_LOMBARD with no maturityDate — the server always computes the next business day', async () => {
    const created = lombardDeal({ state: 'DRAFT', submittedBy: null })
    router = (url, init) => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      if (url.endsWith('/api/v1/treasury/deals') && init?.method === 'POST') return json(created, 201)
      return json({}, 404)
    }
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    fireEvent.change(screen.getByLabelText(/^(Produkt|Product)$/), { target: { value: 'CNB_LOMBARD' } })
    fireEvent.change(screen.getByLabelText(/^(Jistina|Principal)$/), { target: { value: '2500000' } })
    fireEvent.change(screen.getByLabelText(/Lombardní sazba ČNB \(%\)|CNB lombard rate \(%\)/), { target: { value: '5.75' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Create draft|Vytvořit koncept/ })) })

    const post = calls.find(c => c.init?.method === 'POST')!
    const payload = JSON.parse(String(post.init!.body))
    expect(payload).toMatchObject({ product: 'CNB_LOMBARD', counterpartyId: 'CNB', currency: 'CZK', principal: 2500000, rate: 5.75 })
    expect(payload).not.toHaveProperty('maturityDate')
  })
})

describe('deal list and detail — CNB_LOMBARD labelling', () => {
  it('the blotter labels a CNB_LOMBARD row "ČNB lombardní úvěr"', async () => {
    router = url => (url.includes('/counterparties') ? json(COUNTERPARTIES) : json([lombardDeal()]))
    await renderPage(<TreasuryDealsPage />)
    expect(await screen.findByText(/ČNB lombardní úvěr|ČNB lombard borrowing/)).toBeTruthy()
  })

  it('the detail page shows the collateral-not-modelled note for a CNB_LOMBARD deal, but not otherwise', async () => {
    router = (url) => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      return json(lombardDeal())
    }
    await renderPage(<TreasuryDealDetailPage params={Promise.resolve({ id: 'lomb-1' })} />)
    expect(await screen.findByRole('note')).toHaveTextContent(/zástava není v systému evidována|collateral pledge is not modelled/i)
  })

  it('omits the collateral note for a non-lombard deal', async () => {
    router = (url) => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      return json(lombardDeal({ product: 'MM_PLACEMENT', dealId: 'plain-1' }))
    }
    await renderPage(<TreasuryDealDetailPage params={Promise.resolve({ id: 'plain-1' })} />)
    await screen.findByText(/ČNB lombardní úvěr|ČNB lombard borrowing|MM placement|Umístění/)
    expect(screen.queryByRole('note')).toBeNull()
  })
})
