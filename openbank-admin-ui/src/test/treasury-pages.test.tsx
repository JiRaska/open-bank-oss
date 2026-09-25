// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React, { Suspense } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const session = vi.hoisted(() => ({ roles: ['ROLE_TREASURY_APPROVER'] as string[], username: 'petr.approver' }))
const jwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'Petr', roles: session.roles, accessToken: jwt({ preferred_username: session.username, sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))

import TreasuryApprovalsPage from '@/app/treasury/approvals/page'
import NewTreasuryDealPage from '@/app/treasury/deals/new/page'
import TreasuryDealsPage from '@/app/treasury/deals/page'
import TreasuryCounterpartiesPage from '@/app/treasury/counterparties/page'
import TreasuryPositionsPage from '@/app/treasury/positions/page'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const deal = (dealId: string, createdBy: string, submittedBy: string | null = createdBy) => ({
  dealId, product: 'MM_PLACEMENT', counterpartyId: 'SIM-A', currency: 'CZK', principal: 1000000, rate: 4.25,
  dayCount: 'ACT/360', days: 1, interest: 118.06, tradeDate: '2026-09-25', valueDate: '2026-09-25', maturityDate: '2026-09-28',
  state: 'PENDING_APPROVAL', createdBy, createdByType: 'HUMAN', submittedBy, approvedBy: null, rationale: null,
  limitCheck: { currency: 'CZK', limit: 5000000, exposureBefore: 0, exposureAfter: 1000000, headroomAfter: 4000000, breached: false },
  createdAt: '2026-09-25T08:00:00Z', updatedAt: '2026-09-25T08:01:00Z',
  history: [{ from: null, to: 'DRAFT', actor: createdBy, actorType: 'HUMAN', at: '2026-09-25T08:00:00Z', note: null }],
  journals: [],
})
const COUNTERPARTIES = [
  { counterpartyId: 'CNB', name: 'Česká národní banka', kind: 'CENTRAL_BANK', synthetic: false, currency: 'CZK', limit: 1e12, exposure: 0, headroom: 1e12 },
  { counterpartyId: 'SIM-A', name: 'Sim Bank A', kind: 'BANK', synthetic: true, currency: 'CZK', limit: 5000000, exposure: 1000000, headroom: 4000000 },
  { counterpartyId: 'SIM-A', name: 'Sim Bank A', kind: 'BANK', synthetic: true, currency: 'EUR', limit: 200000, exposure: 0, headroom: 200000 },
]

const renderPage = async (node: React.ReactNode) => {
  await act(async () => { render(<LanguageProvider><Suspense fallback={null}>{node}</Suspense></LanguageProvider>) })
}

let calls: { url: string; init?: RequestInit }[] = []
let router: (url: string, init?: RequestInit) => Response

beforeEach(() => {
  calls = []
  session.roles = ['ROLE_TREASURY_APPROVER']
  session.username = 'petr.approver'
  vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => { calls.push({ url: String(u), init }); return router(String(u), init) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

const approveButtons = () => screen.queryAllByRole('button', { name: /^(Schválit|Approve)$/ })

describe('approval inbox — four-eyes in the console', () => {
  beforeEach(() => {
    router = (url, init) => {
      if (url.includes('/deals/other-1/approve')) return json({ error: 'FOUR_EYES_VIOLATION', message: 'approver must differ from the creator' }, 422)
      if (url.includes('/deals/limit-1/approve')) return json({ error: 'LIMIT_BREACHED', message: 'exposure 6000000 > limit 5000000 CZK' }, 422)
      if (url.includes('/api/v1/treasury/deals') && !init?.method) {
        return json([deal('own-1', 'petr.approver'), deal('sub-1', 'dana.dealer', 'petr.approver'), deal('other-1', 'dana.dealer'), deal('limit-1', 'eva.dealer')])
      }
      if (url.includes('/api/v1/treasury/counterparties')) return json(COUNTERPARTIES)
      return json({}, 404)
    }
  })

  it('reads PENDING_APPROVAL deals through the treasury BFF path, never a direct service URL', async () => {
    await renderPage(<TreasuryApprovalsPage />)
    await screen.findAllByText(/dana\.dealer/)
    expect(calls.map(c => c.url)).toContain('/api/svc/treasury-service/api/v1/treasury/deals?state=PENDING_APPROVAL')
  })

  it('hides approve on a deal the viewer created or submitted, and offers it on the others', async () => {
    await renderPage(<TreasuryApprovalsPage />)
    await screen.findAllByText(/dana\.dealer/)
    // four PENDING rows: one created by me, one submitted by me → exactly two approve controls
    expect(approveButtons()).toHaveLength(2)
    expect(screen.getAllByText(/Your deal|Váš obchod/)).toHaveLength(2)
    expect(screen.getAllByText(/Sim Bank A/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Synthetic counterparty|Simulovaná protistrana/).length).toBeGreaterThan(0)
  })

  it('surfaces a 422 FOUR_EYES_VIOLATION readably if it happens anyway', async () => {
    await renderPage(<TreasuryApprovalsPage />)
    await screen.findAllByText(/dana\.dealer/)
    await act(async () => { fireEvent.click(approveButtons()[0]) })
    const status = await screen.findByRole('status')
    expect(status.textContent).toMatch(/four-eyes|čtyř očí/)
    expect(status.textContent).toContain('approver must differ from the creator')
    expect(status.textContent).not.toMatch(/HTTP|422/)
  })

  it('surfaces a 422 LIMIT_BREACHED readably', async () => {
    await renderPage(<TreasuryApprovalsPage />)
    await screen.findAllByText(/eva\.dealer/)
    await act(async () => { fireEvent.click(approveButtons()[1]) })
    const status = await screen.findByRole('status')
    expect(status.textContent).toMatch(/limit breached|překročen limit/)
  })

  it('requires a reason before reject is enabled, and sends it', async () => {
    await renderPage(<TreasuryApprovalsPage />)
    await screen.findAllByText(/dana\.dealer/)
    const reject = screen.getAllByRole('button', { name: /^(Zamítnout|Reject)$/ })[0] as HTMLButtonElement
    expect(reject.disabled).toBe(true)
    fireEvent.change(screen.getAllByLabelText(/Rejection reason|Důvod zamítnutí/)[0], { target: { value: 'rate off market' } })
    expect(reject.disabled).toBe(false)
    router = (url, init) => (init?.method === 'POST' ? json(deal('own-1', 'petr.approver')) : json([]))
    await act(async () => { fireEvent.click(reject) })
    const post = calls.find(c => c.init?.method === 'POST')!
    expect(post.url).toBe('/api/svc/treasury-service/api/v1/treasury/deals/own-1/reject')
    expect(JSON.parse(String(post.init!.body))).toEqual({ reason: 'rate off market' })
  })
})

describe('new deal — dealer form', () => {
  beforeEach(() => {
    session.roles = ['ROLE_TREASURY_DEALER']
    session.username = 'dana.dealer'
    router = url => (url.includes('/counterparties') ? json(COUNTERPARTIES) : json({}, 404))
  })

  it('shows the selected counterparty’s headroom in the chosen currency and warns when the principal exceeds it', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    fireEvent.change(screen.getByLabelText(/^(Protistrana|Counterparty)$/), { target: { value: 'SIM-A' } })
    const headroom = await screen.findByTestId('limit-headroom')
    expect(headroom.textContent).toMatch(/headroom|Volný limit/)
    expect(headroom.textContent).toContain('CZK')
    expect(headroom.textContent).toMatch(/4[\s,. ]?000[\s,. ]?000/)
    expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.change(screen.getByLabelText(/^(Jistina|Principal)$/), { target: { value: '4500000' } })
    expect(screen.getByRole('alert').textContent).toMatch(/LIMIT_BREACHED/)
    fireEvent.change(screen.getByLabelText(/^(Měna|Currency)$/), { target: { value: 'EUR' } })
    expect(screen.getByTestId('limit-headroom').textContent).toContain('EUR')
  })

  it('offers only ČNB, CZK and no maturity for the deposit facility', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    fireEvent.change(screen.getByLabelText(/^(Produkt|Product)$/), { target: { value: 'CNB_DEPOSIT_FACILITY' } })
    const cps = screen.getByLabelText(/^(Protistrana|Counterparty)$/) as HTMLSelectElement
    expect([...cps.options].map(o => o.value)).toEqual(['CNB'])
    expect((screen.getByLabelText(/^(Měna|Currency)$/) as HTMLSelectElement).value).toBe('CZK')
    expect(screen.queryByLabelText(/^(Datum splatnosti|Maturity date)$/)).toBeNull()
  })

  it('creates a DRAFT, then offers Submit', async () => {
    const created = { ...deal('new-1', 'dana.dealer', null), state: 'DRAFT', limitCheck: null }
    router = (url, init) => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      if (url.endsWith('/api/v1/treasury/deals') && init?.method === 'POST') return json(created, 201)
      if (url.endsWith('/deals/new-1/submit')) return json({ ...created, state: 'PENDING_APPROVAL', submittedBy: 'dana.dealer' })
      return json({}, 404)
    }
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    fireEvent.change(screen.getByLabelText(/^(Protistrana|Counterparty)$/), { target: { value: 'SIM-A' } })
    fireEvent.change(screen.getByLabelText(/^(Jistina|Principal)$/), { target: { value: '1000000' } })
    fireEvent.change(screen.getByLabelText(/Annual rate in percent|Roční sazba/), { target: { value: '4.25' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Create draft|Vytvořit koncept/ })) })
    const draftPost = calls.find(c => c.init?.method === 'POST')!
    expect(JSON.parse(String(draftPost.init!.body))).toMatchObject({ product: 'MM_PLACEMENT', counterpartyId: 'SIM-A', currency: 'CZK', principal: 1000000, rate: 4.25 })
    await act(async () => { fireEvent.click(await screen.findByRole('button', { name: /Submit for approval|Předložit ke schválení/ })) })
    const submitCall = calls.find(c => c.url.endsWith('/deals/new-1/submit'))!
    expect(submitCall).toBeTruthy()
    // money-path idempotency gate: every write carries a key, one per intent (draft != submit)
    const draftKey = new Headers(draftPost.init!.headers).get('idempotency-key')
    const submitKey = new Headers(submitCall.init!.headers).get('idempotency-key')
    expect(draftKey).toMatch(/^[0-9a-f-]{36}$/)
    expect(submitKey).toMatch(/^[0-9a-f-]{36}$/)
    expect(submitKey).not.toBe(draftKey)
    expect((await screen.findByRole('status')).textContent).toMatch(/different person|jiná osoba/)
  })
})

describe('read pages', () => {
  it('blotter filters by state through the query string', async () => {
    router = url => (url.includes('/counterparties') ? json(COUNTERPARTIES) : json([deal('d-1', 'dana.dealer')]))
    await renderPage(<TreasuryDealsPage />)
    await screen.findByText('dana.dealer')
    await act(async () => { fireEvent.change(screen.getByLabelText(/Filter by state|Filtr podle stavu/), { target: { value: 'BOOKED' } }) })
    expect(calls.map(c => c.url)).toContain('/api/svc/treasury-service/api/v1/treasury/deals?state=BOOKED')
  })

  it('counterparty limits label the simulated banks as synthetic', async () => {
    router = () => json(COUNTERPARTIES)
    await renderPage(<TreasuryCounterpartiesPage />)
    await screen.findAllByText(/Sim Bank A/)
    expect(screen.getAllByText(/Synthetic counterparty|Simulovaná protistrana/)).toHaveLength(2)
    expect(screen.getAllByRole('meter')).toHaveLength(3)
  })

  it('positions degrade to the shared panel when treasury-service is not deployed', async () => {
    router = () => json({ error: 'Unknown service: treasury-service' }, 404)
    await renderPage(<TreasuryPositionsPage />)
    expect((await screen.findAllByText(/treasury-service/)).length).toBeGreaterThan(0)
    expect(calls[0].url).toMatch(/^\/api\/svc\/treasury-service\/api\/v1\/treasury\/positions\?asOf=\d{4}-\d{2}-\d{2}$/)
  })
})
