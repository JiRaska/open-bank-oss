// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0344 deal ticket and the cash-flow ledger on the deal page: all 16 products, every header
// and per-leg field, the generate-schedule preview, field-level server errors, the explicit "not
// yet available" state, override proposals and the four-eyes decision.
import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, screen, within } from '@testing-library/react'

const session = vi.hoisted(() => ({ roles: ['ROLE_TREASURY_DEALER'] as string[], username: 'dana.dealer' }))
const jwtOf = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'X', roles: session.roles, accessToken: jwtOf({ preferred_username: session.username, sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))

import NewTreasuryDealPage from '@/app/treasury/deals/new/page'
import TreasuryDealDetailPage from '@/app/treasury/deals/[id]/page'
import { bodyOf, type Call, COUNTERPARTIES, deal, flow, json, keyOf, ledger, notServed, renderPage } from './treasury-wb-harness'

let calls: Call[] = []
let router: (url: string, init?: RequestInit) => Response

beforeEach(() => {
  calls = []
  session.roles = ['ROLE_TREASURY_DEALER']
  session.username = 'dana.dealer'
  vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => { calls.push({ url: String(u), init }); return router(String(u), init) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

const label = (re: RegExp) => screen.getByLabelText(re)
const select = (re: RegExp, value: string) => fireEvent.change(label(re), { target: { value } })
const fill = (re: RegExp, value: string) => fireEvent.change(label(re), { target: { value } })
const click = async (name: RegExp) => { await act(async () => { fireEvent.click(screen.getByRole('button', { name })) }) }
const clickTab = async (name: RegExp) => { await act(async () => { fireEvent.click(screen.getByRole('tab', { name })) }) }
const tickets = (url: string) => url.endsWith('/api/v1/treasury/deals')

const baseRouter = (extra: (url: string, init?: RequestInit) => Response | undefined = () => undefined) => (url: string, init?: RequestInit): Response => {
  const e = extra(url, init)
  if (e) return e
  if (url.includes('/counterparties')) return json(COUNTERPARTIES)
  return notServed()
}

describe('deal ticket — product picker and the catalogue’s fields', () => {
  beforeEach(() => { router = baseRouter() })

  it('offers all 16 products', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    const products = (label(/^(Produkt|Product)$/) as HTMLSelectElement).options
    expect(products).toHaveLength(16)
    expect([...products].map(o => o.value)).toEqual(expect.arrayContaining(['CASH', 'TREASURY_BILL', 'BOND', 'BILL_OF_EXCHANGE', 'LOAN', 'FX_FORWARD', 'FX_SWAP', 'FRA', 'IRS', 'REPO', 'REVERSE_REPO']))
  })

  it('shows every header field of the catalogue', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    for (const name of [
      /Legal entity|Právní subjekt/, /^(Protistrana|Counterparty)$/, /Counterparty deal reference|Číslo obchodu protistrany/, /Communication channel|Komunikační kanál/,
      /Portfolio \(book\)|Portfolio \(kniha\)/, /Deal kind|Druh obchodu/, /Trade date and time|Datum a čas obchodu/, /^(Datum valuty|Value date)$/, /Maturity date|Datum splatnosti/,
      /Valid from|Platnost od/, /Valid to|Platnost do/, /Jurisdiction|Jurisdikce/, /Holiday calendars|Kalendáře svátků/, /Business-day convention|Konvence pracovních dnů/,
      /Schedule generation direction|Směr generování/, /Fixed convention|Pevná konvence/, /Notional exchange|Výměna jistiny/, /Single currency|Jedna měna/, /^(Note|Poznámka)$/,
    ]) expect(label(name), String(name)).toBeTruthy()
  })

  it('shows every per-leg field on both legs of an IRS', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    select(/^(Produkt|Product)$/, 'IRS')
    for (const name of [
      /Leg side|Strana nohy/, /Leg currency|Měna nohy/, /Leg notional|Jistina nohy/, /^(Amortisation|Amortizace)$/, /Amortisation start|Začátek amortizace/, /Accrual start|Začátek akruálu/,
      /Accrual end|Konec akruálu/, /Accrual frequency|Frekvence akruálu/, /Payment frequency|Frekvence plateb/, /Day count \(accrual\)|Konvence dní \(akruál\)/,
      /Payment lag|Odklad platby/, /Payment calendars|Platební kalendáře/, /Rate type|Typ sazby/,
    ]) expect(screen.getAllByLabelText(name), String(name)).toHaveLength(2)
    // leg 2 defaults to floating: index, spread, refixing, lead, first-fixing rate, cap, floor and what they apply to
    for (const name of [/Reference index|Referenční index/, /Spread|Marže/, /Refixing frequency|Frekvence refixace/, /Fixing lead|Předstih fixingu/, /until first fixing|do prvního fixingu/, /^(Cap|Strop)/, /^(Floor|Spodní)/, /applies to|se vztahuje na/]) {
      expect(screen.getAllByLabelText(name).length, String(name)).toBeGreaterThanOrEqual(1)
    }
    expect(screen.getByText(/Leg 1: pay|Noha 1: platím/)).toBeTruthy()
    expect(screen.getByText(/Leg 2: receive|Noha 2: přijímám/)).toBeTruthy()
  })

  it('renders the product-specific panels: securities, FX swap, FRA, repo collateral, deposit', async () => {
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    select(/^(Produkt|Product)$/, 'BOND')
    expect(label(/Security \(ISIN\)|Cenný papír \(ISIN\)/)).toBeTruthy()
    expect(label(/^(Nominal|Nominál)$/)).toBeTruthy()
    expect(label(/Accrued interest|Naběhlý úrok/)).toBeTruthy()
    expect(label(/Settlement place|Místo vypořádání/)).toBeTruthy()
    expect(label(/Custody|úschova/)).toBeTruthy()
    select(/^(Produkt|Product)$/, 'FX_SWAP')
    for (const n of [/Buy amount|Kupovaná částka/, /Sell amount|Prodávaná částka/, /Forward points|Forwardové body/, /Near-leg date|Datum blízké nohy/, /Far-leg date|Datum vzdálené nohy/, /Near-leg rate|Kurz blízké nohy/, /Far-leg rate|Kurz vzdálené nohy/]) expect(label(n), String(n)).toBeTruthy()
    select(/^(Produkt|Product)$/, 'FRA')
    expect(label(/Contract period|Období kontraktu/)).toBeTruthy()
    expect(label(/Settlement convention|Konvence vypořádání/)).toBeTruthy()
    select(/^(Produkt|Product)$/, 'REPO')
    await clickTab(/^(Collateral|Zajištění)$/)
    for (const n of [/Collateral security|Zajišťovací cenný papír/, /Collateral nominal|Nominál zajištění/, /Dirty price|Špinavá cena/, /Haircut/, /Substitution|výměnu zajištění/, /Margin-call|margin callu/]) expect(label(n), String(n)).toBeTruthy()
    select(/^(Produkt|Product)$/, 'LOAN')
    expect(label(/Principal account|Účet jistiny/)).toBeTruthy()
    expect(label(/Interest account|Účet úroků/)).toBeTruthy()
    expect(label(/Early-withdrawal|předčasného výběru/)).toBeTruthy()
  })

  it('has all ten tabs and reaches each with the keyboard-operable tablist', async () => {
    await renderPage(<NewTreasuryDealPage />)
    const tabs = screen.getAllByRole('tab')
    expect(tabs).toHaveLength(10)
    await clickTab(/^(Documents|Dokumenty)$/)
    await click(/Add document|Přidat dokument/)
    expect(label(/Document 1 id|ID dokumentu 1/)).toBeTruthy()
    await clickTab(/^(Fees|Poplatky)$/)
    await click(/Add fee|Přidat poplatek/)
    expect(label(/Fee 1 amount|Částka poplatku 1/)).toBeTruthy()
    await clickTab(/Linked deals|Propojené obchody/)
    expect(label(/Original deal|Původní obchod/)).toBeTruthy()
    await clickTab(/Central-bank codes|Kódy ČNB/)
    expect(label(/^UTI$/)).toBeTruthy()
    await clickTab(/LCR\/NSFR/)
    expect(label(/LCR category|Kategorie LCR/)).toBeTruthy()
    expect(label(/NSFR category|Kategorie NSFR/)).toBeTruthy()
    await clickTab(/Credit-exposure check|Kontrola expozice/)
    expect(screen.getByText(/runs at submit|provede při předložení/)).toBeTruthy()
    await clickTab(/Payment orders|Platební příkazy/)
    expect(screen.getByText(/generated from a saved deal|vznikají z uloženého/)).toBeTruthy()
    await clickTab(/State & timeline|Stav a průběh/)
    expect(screen.getByText(/starts as a Draft|začne ve stavu Koncept/)).toBeTruthy()
  })
})

describe('deal ticket — generate schedule and server refusals', () => {
  const draft = deal('d-1', { product: 'IRS', bookId: 'BANKING' })
  const fillIrs = async () => {
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    select(/^(Produkt|Product)$/, 'IRS')
    select(/^(Protistrana|Counterparty)$/, 'SIM-A')
    fill(/Portfolio \(book\)|Portfolio \(kniha\)/, 'BANKING')
    fireEvent.change(screen.getAllByLabelText(/Leg notional|Jistina nohy/)[0], { target: { value: '1000000' } })
    fireEvent.change(screen.getAllByLabelText(/Leg notional|Jistina nohy/)[1], { target: { value: '1000000' } })
    for (const el of screen.getAllByLabelText(/Accrual start|Začátek akruálu/)) fireEvent.change(el, { target: { value: '2026-10-21' } })
    for (const el of screen.getAllByLabelText(/Accrual end|Konec akruálu/)) fireEvent.change(el, { target: { value: '2027-10-21' } })
    fireEvent.change(screen.getByLabelText(/Fixed rate|Pevná sazba/), { target: { value: '4.25' } })
    fireEvent.change(screen.getByLabelText(/Reference index|Referenční index/), { target: { value: 'PRIBOR_3M' } })
  }

  it('drafts the deal, previews the ledger inline and does not submit', async () => {
    router = baseRouter((url, init) => {
      if (tickets(url) && init?.method === 'POST') return json(draft, 201)
      if (url.endsWith('/deals/d-1/schedule-preview')) return json(ledger([flow(0), flow(1, { rateSource: 'MISSING', amount: null })]))
      return undefined
    })
    await fillIrs()
    await click(/Generate schedule|Vygenerovat harmonogram/)
    const post = calls.find(c => tickets(c.url) && c.init?.method === 'POST')!
    expect(bodyOf(post)).toMatchObject({ product: 'IRS', bookId: 'BANKING', counterpartyId: 'SIM-A' })
    expect(bodyOf(post).legs).toHaveLength(2)
    const preview = calls.find(c => c.url.endsWith('/schedule-preview'))!
    expect(preview.init?.method).toBe('POST')
    expect(keyOf(preview)).not.toBe(keyOf(post))
    const grid = await screen.findByTestId('cashflow-grid')
    expect(within(grid).getAllByRole('row')).toHaveLength(3) // header + 2 flows
    expect(screen.getByTestId('preview-note').textContent).toMatch(/nothing has been stored|nic nebylo uloženo/i)
    expect(within(grid).getByText(/Missing|Chybí/)).toBeTruthy()
    expect(calls.some(c => c.url.endsWith('/submit'))).toBe(false)
    // generating again reuses the saved draft: no second POST /deals
    await click(/Generate schedule|Vygenerovat harmonogram/)
    expect(calls.filter(c => tickets(c.url) && c.init?.method === 'POST')).toHaveLength(1)
  })

  it('says "not yet available" instead of a blank panel when the service has no schedule-preview', async () => {
    router = baseRouter((url, init) => (tickets(url) && init?.method === 'POST' ? json(draft, 201) : undefined))
    await fillIrs()
    await click(/Generate schedule|Vygenerovat harmonogram/)
    expect((await screen.findByTestId('not-available')).textContent).toMatch(/Not yet available on this deployment|Zatím není dostupné/)
    expect(screen.queryByTestId('cashflow-grid')).toBeNull()
  })

  it('renders REFERENCE_NOT_EFFECTIVE on the book, calendar, index and security fields it names', async () => {
    router = baseRouter((url, init) => (tickets(url) && init?.method === 'POST'
      ? json({ error: 'REFERENCE_NOT_EFFECTIVE', message: 'not effective', missingReferences: ['book:BANKING', 'calendar:CERTIS', 'index:PRIBOR_3M'] }, 422)
      : undefined))
    await fillIrs()
    fill(/Holiday calendars|Kalendáře svátků/, 'CERTIS')
    await click(/Create draft|Vytvořit koncept/)
    const alerts = (await screen.findAllByRole('alert')).map(a => a.textContent)
    expect(alerts.some(a => /book:BANKING/.test(a ?? ''))).toBe(true)
    expect(alerts.some(a => /calendar:CERTIS/.test(a ?? ''))).toBe(true)
    expect(alerts.some(a => /index:PRIBOR_3M/.test(a ?? ''))).toBe(true)
    // the message sits under the field it concerns, wired by aria-describedby
    const book = label(/Portfolio \(book\)|Portfolio \(kniha\)/)
    expect(book.getAttribute('aria-invalid')).toBe('true')
    expect(document.getElementById(book.getAttribute('aria-describedby')!)?.textContent).toMatch(/book:BANKING/)
  })

  it('renders PRODUCT_LIMIT_BREACHED breaches under principal for a money-market deal', async () => {
    router = baseRouter((url, init) => (tickets(url) && init?.method === 'POST'
      ? json({ error: 'PRODUCT_LIMIT_BREACHED', message: 'x', breaches: [{ rule: 'MAX_PRINCIPAL', limit: '500000', actual: '1000000' }] }, 422)
      : undefined))
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    select(/^(Protistrana|Counterparty)$/, 'SIM-A')
    fill(/^(Jistina|Principal)$/, '1000000')
    fill(/Annual rate in percent|Roční sazba/, '4.25')
    await click(/Create draft|Vytvořit koncept/)
    const principal = label(/^(Jistina|Principal)$/)
    await screen.findByText(/product limit 500000|produktový limit 500000/)
    expect(document.getElementById(principal.getAttribute('aria-describedby')!)?.textContent).toMatch(/500000/)
  })

  it('refuses an incomplete ticket client-side, with field errors and no request', async () => {
    router = baseRouter()
    await renderPage(<NewTreasuryDealPage />)
    await screen.findAllByRole('option', { name: /Sim Bank A/ })
    select(/^(Produkt|Product)$/, 'IRS')
    await click(/Create draft|Vytvořit koncept/)
    expect((await screen.findAllByText(/Select a counterparty|Vyberte protistranu/)).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Notional must be a positive number|Jistina musí být kladné číslo/)).toHaveLength(2)
    expect(calls.some(c => c.init?.method === 'POST')).toBe(false)
  })

  it('a hypothetical deal is priced pre-trade (risk-engine) and can never be submitted', async () => {
    router = baseRouter((url, init) => {
      if (tickets(url) && init?.method === 'POST') return json({ ...draft, kind: 'HYPOTHETICAL' }, 201)
      if (url.endsWith('/api/svc/risk-engine/api/v1/risk/pricing')) return json({ pv: 12345.67, pv01: -88.1, currency: 'CZK', provenance: 'synthetic', dv01Buckets: [{ bucket: '1Y', dv01: -50 }] })
      return undefined
    })
    await fillIrs()
    select(/Deal kind|Druh obchodu/, 'HYPOTHETICAL')
    await click(/Price pre-trade|Předběžně ocenit/)
    const priced = await screen.findByTestId('pricing-result')
    expect(priced.textContent).toMatch(/12[\s ]345,67|12,345\.67/)
    expect(bodyOf(calls.find(c => c.url.endsWith('/risk/pricing'))!).legs).toHaveLength(2)
    await click(/Create draft|Vytvořit koncept/)
    await screen.findByText(/never submitted|nepředkládá/)
    expect(screen.queryByRole('button', { name: /Submit for approval|Předložit ke schválení/ })).toBeNull()
  })
})

// ── the deal page: ledger, overrides, four-eyes, history ───────────────────────────────────────
const detail = async (d: Record<string, unknown>) => {
  await renderPage(<TreasuryDealDetailPage params={Promise.resolve({ id: String(d.dealId) })} />)
}
const ledgerRouter = (d: Record<string, unknown>, ledgerBody: unknown, extra: (url: string, init?: RequestInit) => Response | undefined = () => undefined) =>
  baseRouter((url, init) => {
    const e = extra(url, init)
    if (e) return e
    if (/\/deals\/d-1$/.test(url)) return json(d)
    if (url.endsWith('/cash-flows')) return ledgerBody === null ? notServed() : json(ledgerBody)
    if (url.endsWith('/cash-flows/conflicts')) return json([])
    return undefined
  })

describe('deal page — cash-flow ledger', () => {
  const booked = deal('d-1', { state: 'BOOKED', createdBy: 'dana.dealer', submittedBy: 'dana.dealer', approvedBy: 'amy.approver' })

  it('lists every flow with kind, direction, rate source, original beside override, manual badge', async () => {
    router = ledgerRouter(booked, ledger([
      flow(0),
      flow(1, { rateSource: 'OBSERVED', state: 'FIXED', paymentDate: '2027-01-25', original: { paymentDate: '2027-01-21', amount: 10590.28 }, amount: 12000, override: { overrideId: 'o-1', reason: 'agreed with the bank', by: 'petr.back', approvedBy: 'amy.approver' } }),
      flow(2, { kind: 'FEE', manual: true, direction: 'OUT', rateSource: 'FIXED', rate: null }),
    ]))
    await detail(booked)
    const grid = await screen.findByTestId('cashflow-grid')
    const rows = within(grid).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(within(rows[1]).getAllByLabelText(/original|původní/, { selector: 's' })).toHaveLength(2)
    expect(within(rows[1]).getByText(/Approved by amy.approver|Schválil amy.approver/)).toBeTruthy()
    expect(within(rows[2]).getByText(/^(Manual|Ruční)$/)).toBeTruthy()
    expect(within(rows[0]).getAllByText(/Projected|Projekce/)).toHaveLength(2) // rate source and state
  })

  it('says "not yet available" when the deployment serves no ledger, and never fakes rows', async () => {
    router = ledgerRouter(booked, null)
    await detail(booked)
    expect((await screen.findByTestId('not-available')).textContent).toMatch(/cash-flow ledger|Harmonogram peněžních toků/)
    expect(screen.queryByTestId('cashflow-grid')).toBeNull()
  })

  it('shows a conflicts banner for approved overrides a regeneration could not carry over', async () => {
    router = ledgerRouter(booked, ledger([flow(0)]), url => (url.endsWith('/cash-flows/conflicts')
      ? json([{ conflictId: 'c-1', overrideId: 'o-1', legIndex: 0, periodIndex: 1, detectedAt: '2026-10-19T08:00:00Z', reason: 'leg spec changed' }])
      : undefined))
    await detail(booked)
    const banner = await screen.findByTestId('conflicts-banner')
    expect(banner.textContent).toMatch(/leg spec changed/)
    expect(banner.textContent).toMatch(/1/)
  })

  it('back office proposes a date-and-reason override: PENDING, with its own idempotency key', async () => {
    session.roles = ['ROLE_TREASURY_BACK_OFFICE', 'ROLE_TREASURY_DEALER']
    session.username = 'petr.back'
    router = ledgerRouter(booked, ledger([flow(0)]), (url, init) => (url.endsWith('/cash-flows/f-0/overrides') && init?.method === 'POST'
      ? json({ overrideId: 'o-9', dealId: 'd-1', flowId: 'f-0', status: 'PENDING', paymentDate: '2027-01-25', amount: null, reason: 'holiday', proposedBy: 'petr.back', proposedAt: '2026-10-19T09:00:00Z' }, 201)
      : undefined))
    await detail(booked)
    await screen.findByTestId('cashflow-grid')
    await click(/Override flow|Přepsat tok/)
    // an empty submit is refused client-side
    await click(/Propose override|Navrhnout přepsání/)
    expect(await screen.findByText(/Enter a new payment date or a new amount|Zadejte nové datum/)).toBeTruthy()
    fill(/New payment date|Nové datum platby/, '2027-01-25')
    fill(/Reason \(required\)|Důvod \(povinný\)/, 'holiday')
    await click(/Propose override|Navrhnout přepsání/)
    const post = calls.find(c => c.url.endsWith('/cash-flows/f-0/overrides'))!
    expect(bodyOf(post)).toEqual({ paymentDate: '2027-01-25', amount: null, reason: 'holiday' })
    expect(keyOf(post)).toMatch(/^[0-9a-f-]{36}$/)
    expect((await screen.findAllByText(/Pending: petr.back|Čeká: petr.back/)).length).toBeGreaterThan(0)
    // the proposer cannot decide their own override
    expect(screen.queryByRole('button', { name: /^(Approve|Schválit)$/ })).toBeNull()
    expect(screen.getByText(/You proposed it|Navrhli jste/)).toBeTruthy()
  })

  it('offers no override control to a dealer without the back-office role', async () => {
    router = ledgerRouter(booked, ledger([flow(0)]))
    await detail(booked)
    await screen.findByTestId('cashflow-grid')
    expect(screen.queryByRole('button', { name: /Override flow|Přepsat tok/ })).toBeNull()
  })

  it('a different approver sees approve / reject on a pending override, the proposer does not; 422 is rendered', async () => {
    const pendingFlow = flow(0, { override: { overrideId: 'o-5', reason: 'rate agreed', by: 'petr.back', approvedBy: null } })
    session.roles = ['ROLE_TREASURY_APPROVER']
    session.username = 'amy.approver'
    router = ledgerRouter(booked, ledger([pendingFlow]), (url, init) => (url.endsWith('/overrides/o-5/approve') && init?.method === 'POST'
      ? json({ error: 'FOUR_EYES_VIOLATION', message: 'proposer may not approve' }, 422) : undefined))
    await detail(booked)
    await screen.findByTestId('cashflow-grid')
    await click(/^(Approve|Schválit)$/)
    expect((await screen.findAllByRole('alert')).map(a => a.textContent).join(' ')).toMatch(/different person|jiná osoba/)
    expect(calls.some(c => c.url.endsWith('/overrides/o-5/approve'))).toBe(true)
    cleanup()
    calls = []
    session.username = 'petr.back'
    await detail(booked)
    await screen.findByTestId('cashflow-grid')
    expect(screen.queryByRole('button', { name: /^(Approve|Schválit)$/ })).toBeNull()
  })

  it('opens the change history and the send-confirmation action (withheld from the deal’s creator)', async () => {
    session.roles = ['ROLE_TREASURY_BACK_OFFICE']
    session.username = 'petr.back'
    router = ledgerRouter(booked, ledger([flow(0)]), (url, init) => {
      if (url.endsWith('/deals/d-1/history')) return json({ transitions: [], versions: [{ version: 2, scheduleVersion: 2, createdBy: 'dana.dealer', createdAt: '2026-10-19T09:00:00Z', changes: ['rate 4.25 → 4.30'] }] })
      if (url.endsWith('/send-confirmation') && init?.method === 'POST') return json(booked)
      return undefined
    })
    await detail(booked)
    await click(/Change history|Historie změn/)
    expect((await screen.findByTestId('deal-history')).textContent).toMatch(/rate 4\.25 → 4\.30/)
    await click(/Send confirmation|Odeslat konfirmaci/)
    expect(calls.find(c => c.url.endsWith('/send-confirmation'))?.init?.method).toBe('POST')
    cleanup()
    session.username = 'dana.dealer'
    session.roles = ['ROLE_TREASURY_BACK_OFFICE']
    await detail(booked)
    await screen.findByTestId('cashflow-grid')
    expect(screen.queryByRole('button', { name: /Send confirmation|Odeslat konfirmaci/ })).toBeNull()
  })

  it('edits a DRAFT in the ticket; an unserved update shows "not available", not a silent success', async () => {
    const drafted = deal('d-1', { state: 'DRAFT' })
    router = ledgerRouter(drafted, ledger([]), (url, init) => (/\/deals\/d-1$/.test(url) && init?.method === 'PUT' ? notServed() : undefined))
    await detail(drafted)
    await click(/Edit deal ticket|Upravit tiket obchodu/)
    await click(/Save changes|Uložit změny/)
    expect((await screen.findAllByText(/does not offer this action yet|tuto akci toto nasazení zatím nenabízí/)).length).toBeGreaterThan(0)
    expect(calls.find(c => c.init?.method === 'PUT')?.url).toMatch(/\/deals\/d-1$/)
  })
})
