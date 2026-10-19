// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0344 workbench pages: for each page its render, one interaction, the explicit "not yet
// available on this deployment" state when the service answers 404, and the refusal a server gives.
import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'

const session = vi.hoisted(() => ({ roles: ['ROLE_TREASURY_APPROVER'] as string[], username: 'amy.approver' }))
const jwtOf = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'X', roles: session.roles, accessToken: jwtOf({ preferred_username: session.username, sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))

import TreasuryDealsPage from '@/app/treasury/deals/page'
import TreasuryCalendarPage from '@/app/treasury/calendar/page'
import TreasuryCashFlowAnalysisPage from '@/app/treasury/analysis/cash-flows/page'
import TreasuryFixingsPage from '@/app/treasury/fixings/page'
import TreasuryMarketDataPage from '@/app/treasury/market-data/page'
import TreasuryPaymentsPage from '@/app/treasury/payments/page'
import TreasuryReferencePage from '@/app/treasury/reference/page'
import TreasuryReportsPage from '@/app/treasury/reports/page'
import TreasuryDayCompletionPage from '@/app/treasury/day-completion/page'
import { bodyOf, type Call, COUNTERPARTIES, deal, json, keyOf, notServed, renderPage } from './treasury-wb-harness'

let calls: Call[] = []
let router: (url: string, init?: RequestInit) => Response

beforeEach(() => {
  calls = []
  session.roles = ['ROLE_TREASURY_APPROVER']
  session.username = 'amy.approver'
  vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => { calls.push({ url: String(u), init }); return router(String(u), init) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

const click = async (name: RegExp, role: 'button' | 'tab' = 'button') => { await act(async () => { fireEvent.click(screen.getByRole(role, { name })) }) }
const change = async (re: RegExp, value: string) => { await act(async () => { fireEvent.change(screen.getByLabelText(re), { target: { value } }) }) }
const notAvailable = async () => expect((await screen.findByTestId('not-available')).textContent).toMatch(/Not yet available on this deployment|Zatím není dostupné/)
const T = '/api/svc/treasury-service/api/v1/treasury'
const R = '/api/svc/risk-engine/api/v1/risk'

// ── deal overview ──────────────────────────────────────────────────────────────────────────────
describe('deal overview', () => {
  const ov = (id: string, over: Record<string, unknown> = {}) => deal(id, { state: 'BOOKED', bookId: 'BANKING', kind: 'ACTUAL', originalDealId: 'd-0', notional: 1000000, counterpartyName: 'Sim Bank A', lastChangedAt: '2026-10-19T10:00:00Z', lastChangedBy: 'petr.back', ...over })

  it('shows the catalogue columns, the service’s total count, and sends the filters it is given', async () => {
    router = url => (url.includes('/counterparties') ? json(COUNTERPARTIES)
      : url.includes('/views/deal-filters') ? json([])
        : json([ov('d-1'), ov('d-2')], 200, { 'X-Total-Count': '137' }))
    await renderPage(<TreasuryDealsPage />)
    expect((await screen.findByTestId('record-count')).textContent).toMatch(/137/)
    expect(screen.getByText(/loaded 2|načteno 2/)).toBeTruthy()
    for (const h of [/^(Deal|Obchod)$/, /Instrument|Nástroj/, /Original deal|Původní obchod/, /^(Portfolio)$/, /^(Kind|Druh)$/, /Last change|Poslední změna/, /Trade \/ value \/ maturity|Obchod \/ valuta/]) {
      expect(screen.getByRole('columnheader', { name: h }), String(h)).toBeTruthy()
    }
    await change(/^(Instrument|Nástroj)$/, 'IRS')
    await change(/^(Currency|Měna)$/, 'eur')
    await act(async () => { fireEvent.click(screen.getByLabelText(/Hide matured|Skrýt splatné/)) })
    await change(/Free text|Hledat text/, 'abc')
    await change(/Maturity from|Splatnost od/, '2026-12-01')
    const last = calls.filter(c => c.url.startsWith(`${T}/deals?`)).pop()!
    const q = new URL(last.url, 'http://x').searchParams
    expect(Object.fromEntries(q)).toEqual({ instrument: 'IRS', currency: 'EUR', hideMatured: 'true', text: 'abc', maturityFrom: '2026-12-01' })
  })

  it('exports all, the selection and the regulatory layout through /deals/export', async () => {
    const created: string[] = []
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => { created.push('blob'); return 'blob:x' }, revokeObjectURL: () => undefined }))
    router = url => (url.includes('/counterparties') ? json(COUNTERPARTIES)
      : url.includes('/deals/export') ? new Response('id\nd-1', { status: 200, headers: { 'content-type': 'text/csv' } })
        : url.includes('/views/deal-filters') ? json([]) : json([ov('d-1'), ov('d-2')]))
    await renderPage(<TreasuryDealsPage />)
    await screen.findByTestId('record-count')
    expect((screen.getByRole('button', { name: /Export selection|Exportovat výběr/ }) as HTMLButtonElement).disabled).toBe(true)
    await act(async () => { fireEvent.click(screen.getAllByRole('checkbox', { name: /Select deal|Vybrat obchod/ })[1]) })
    await click(/Export selection|Exportovat výběr/)
    await click(/Export in regulatory layout|regulatorním formátu/)
    await click(/Export all|Exportovat vše/)
    const exports = calls.filter(c => c.url.includes('/deals/export')).map(c => Object.fromEntries(new URL(c.url, 'http://x').searchParams))
    expect(exports[0]).toMatchObject({ scope: 'selection', ids: 'd-2', layout: 'STANDARD', format: 'csv' })
    expect(exports[1]).toMatchObject({ scope: 'all', layout: 'REGULATORY' })
    expect(exports[2]).toMatchObject({ scope: 'all', layout: 'STANDARD' })
    expect(created).toHaveLength(3)
  })

  it('saves a personal filter and says so plainly when the service has no saved views', async () => {
    router = (url, init) => (url.includes('/counterparties') ? json(COUNTERPARTIES)
      : url.includes('/views/deal-filters') ? (init?.method === 'POST' ? json({ name: 'mine', filter: { state: 'BOOKED' } }, 201) : notServed())
        : json([ov('d-1')]))
    await renderPage(<TreasuryDealsPage />)
    await screen.findByTestId('record-count')
    expect(screen.getByText(/Personal filters are not available|Osobní filtry toto nasazení zatím nenabízí/)).toBeTruthy()
    await change(/Filter by state|Filtr podle stavu/, 'BOOKED')
    await change(/Personal filter name|Název osobního filtru/, 'mine')
    await click(/Save filter|Uložit filtr/)
    expect(bodyOf(calls.find(c => c.init?.method === 'POST')!)).toEqual({ name: 'mine', filter: { state: 'BOOKED' } })
  })

  it('back office closes the day and runs a special close; the approver sees no close controls', async () => {
    router = (url, init) => (url.includes('/counterparties') ? json(COUNTERPARTIES)
      : url.includes('/positions/close-of-day') ? json({ snapshotId: 'abcdef0123', special: url.includes('special') }, 201)
        : url.includes('/views/deal-filters') ? json([]) : json([ov('d-1')]))
    await renderPage(<TreasuryDealsPage />)
    await screen.findByTestId('record-count')
    expect(screen.queryByRole('button', { name: /Position close of day|Uzávěrka pozic/ })).toBeNull()
    cleanup()
    session.roles = ['ROLE_TREASURY_BACK_OFFICE']
    session.username = 'petr.back'
    await renderPage(<TreasuryDealsPage />)
    await screen.findByTestId('record-count')
    await change(/Special-close scenario|Scénář zvláštní/, 'rates +200bp')
    await click(/^(Position close of day|Uzávěrka pozic)$/)
    await click(/Special close|Zvláštní uzávěrka/)
    const posts = calls.filter(c => c.url.includes('/close-of-day'))
    expect(posts[0].url.endsWith('/close-of-day')).toBe(true)
    expect(bodyOf(posts[0])).toMatchObject({ scenario: null })
    expect(posts[1].url.endsWith('/close-of-day/special')).toBe(true)
    expect(bodyOf(posts[1])).toMatchObject({ scenario: 'rates +200bp' })
    expect(keyOf(posts[0])).not.toBe(keyOf(posts[1]))
  })

  it('degrades to the shared panel when treasury-service is not deployed', async () => {
    router = () => json({ error: 'Unknown service: treasury-service' }, 404)
    await renderPage(<TreasuryDealsPage />)
    expect((await screen.findAllByText(/treasury-service/)).length).toBeGreaterThan(0)
  })

  it('says "not yet available" when the deployment does not serve the filtered overview', async () => {
    router = () => notServed()
    await renderPage(<TreasuryDealsPage />)
    await notAvailable()
  })
})

// ── event calendar ─────────────────────────────────────────────────────────────────────────────
describe('event calendar', () => {
  const ev = { date: '2026-10-21', type: 'REFIXING', description: 'PRIBOR 3M refixing', dealId: 'd-1', flowId: 'f-1', paymentId: 'p-1', responsibleRole: 'ROLE_TREASURY_BACK_OFFICE' }

  it('lists events with links to the deal and the payment, and queries by preset', async () => {
    router = () => json([ev])
    await renderPage(<TreasuryCalendarPage />)
    await screen.findByText('PRIBOR 3M refixing')
    expect(screen.getByRole('link', { name: /Open deal|Otevřít obchod/ }).getAttribute('href')).toBe('/treasury/deals/d-1')
    expect(screen.getByRole('link', { name: /Open payment|Otevřít platbu/ }).getAttribute('href')).toBe('/treasury/payments?paymentId=p-1')
    expect(screen.getByText('ROLE_TREASURY_BACK_OFFICE')).toBeTruthy()
    const week = new URL(calls[0].url, 'http://x').searchParams
    expect(new Date(week.get('to')!).getTime() - new Date(week.get('from')!).getTime()).toBe(6 * 86400000)
    await click(/^(Tomorrow|Zítra)$/)
    const tomorrow = new URL(calls.at(-1)!.url, 'http://x').searchParams
    expect(tomorrow.get('from')).toBe(tomorrow.get('to'))
    await click(/Custom range|Vlastní období/)
    expect(screen.getByLabelText(/^(From|Od)$/)).toBeTruthy()
    await act(async () => { fireEvent.click(screen.getByLabelText(/Margin call/)) })
    expect(new URL(calls.at(-1)!.url, 'http://x').searchParams.get('types')).toBe('MARGIN_CALL')
  })

  it('says "not yet available" when the calendar is not served', async () => {
    router = () => notServed()
    await renderPage(<TreasuryCalendarPage />)
    await notAvailable()
  })
})

// ── cash-flow analysis ─────────────────────────────────────────────────────────────────────────
describe('cash-flow analysis', () => {
  const result = {
    to: '2027-11-02',
    series: [
      { currency: 'CZK', buckets: [{ date: '2026-11-02', inflow: 100, outflow: -40, net: 60, cumulative: 60, value: 60 }] },
      { currency: 'EUR', buckets: [{ date: '2026-11-05', inflow: 7, outflow: 0, net: 7, cumulative: 7, value: 7 }] },
    ],
    reference: null,
    flows: [
      { date: '2026-11-02', currency: 'CZK', amount: 100, referenceAmount: null, dealId: 'd-1', legIndex: 0, flowId: 'f-1', kind: 'INTEREST', dealKind: 'ACTUAL', state: 'PROJECTED' },
      { date: '2026-11-02', currency: 'CZK', amount: -40, referenceAmount: null, dealId: 'd-2', legIndex: 1, flowId: 'f-2', kind: 'PRINCIPAL', dealKind: 'PLANNED', state: 'PROJECTED' },
      { date: '2026-11-05', currency: 'EUR', amount: 7, referenceAmount: null, dealId: 'd-3', legIndex: 0, flowId: 'f-3', kind: 'INTEREST', dealKind: 'ACTUAL', state: 'FIXED' },
    ],
    flowsTruncated: false,
    fxRates: [],
    dataGaps: [{ currency: 'EUR', reason: 'no approved FX_EUR_CZK fixing on 2026-10-19' }],
  }

  it('sends every parameter, shows the service’s buckets and gaps, and drills a bucket down to deal and leg', async () => {
    router = () => json(result)
    await renderPage(<TreasuryCashFlowAnalysisPage />)
    await change(/Portfolio node|Uzel portfolia/, 'BANKING')
    await change(/Reference currency|Referenční měna/, 'EUR')
    await change(/Result expression|Forma výsledku/, 'NET')
    await change(/Bank account|Bankovní účet/, 'NOSTRO-1')
    await change(/Horizon|Horizont/, '90')
    await act(async () => { fireEvent.click(screen.getByLabelText(/Convert to reference|Převést do referenční/)) })
    await act(async () => { fireEvent.click(screen.getByLabelText(/Include planned|Včetně plánovaných/)) })
    await click(/Run analysis|Spustit analýzu/)
    expect(bodyOf(calls[0])).toMatchObject({
      bookId: 'BANKING', referenceCurrency: 'EUR', convertToReference: true, expression: 'NET', scenario: 'CONTRACTUAL',
      splitMonthly: false, includePlanned: true, horizonDays: 90, bankAccountId: 'NOSTRO-1',
    })
    expect(bodyOf(calls[0]).calculationDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(await screen.findByRole('heading', { name: /Currency CZK|Měna CZK/ })).toBeTruthy()
    expect(screen.getByTestId('data-gaps').textContent).toMatch(/FX_EUR_CZK/)
    await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: /Show flows \(2\)|Zobrazit toky \(2\)/ })[0]) })
    expect(screen.getAllByRole('link', { name: /^(Deal|Obchod)$/ })[0].getAttribute('href')).toBe('/treasury/deals/d-1')
    expect(screen.getByText(/leg 2 · PRINCIPAL · planned|noha 2 · PRINCIPAL · plánovaný/)).toBeTruthy()
  })

  it('explains a refused behavioural or stress scenario instead of showing a contractual figure', async () => {
    router = () => json({ error: 'SCENARIO_NOT_AVAILABLE', message: 'no model' }, 422)
    await renderPage(<TreasuryCashFlowAnalysisPage />)
    await change(/Payment scenario|Scénář plateb/, 'STRESS')
    await click(/Run analysis|Spustit analýzu/)
    expect(bodyOf(calls[0]).scenario).toBe('STRESS')
    expect((await screen.findByRole('alert')).textContent).toMatch(/not available yet|zatím nejsou k dispozici/)
  })

  it('says "not yet available" instead of an empty result when the analysis is not served', async () => {
    router = () => notServed()
    await renderPage(<TreasuryCashFlowAnalysisPage />)
    await click(/Run analysis|Spustit analýzu/)
    await notAvailable()
  })

  it('refuses to render a result it cannot read', async () => {
    router = () => json({ unexpected: true })
    await renderPage(<TreasuryCashFlowAnalysisPage />)
    await click(/Run analysis|Spustit analýzu/)
    expect(await screen.findByText(/unexpected shape|neočekávaný tvar/)).toBeTruthy()
  })
})

// ── fixings ────────────────────────────────────────────────────────────────────────────────────
describe('fixings', () => {
  const row = (over: Record<string, unknown>) => ({ index: 'PRIBOR_3M', fixingDate: '2026-10-19', status: 'DUE', rate: null, source: 'CNB', observedAt: null, dealCount: 3, flowIds: [], blockReason: null, quality: { stale: false, jumpBp: 0, missing: false }, ...over })

  it('shows quality flags, runs the fixing and reports fixed and blocked counts', async () => {
    session.roles = ['ROLE_TREASURY_BACK_OFFICE']
    session.username = 'petr.back'
    router = (url, init) => (url.includes('/fixings/run') ? json({ fixed: [{}, {}], blocked: [{}] })
      : init?.method === 'POST' ? notServed()
        : json([row({ status: 'BLOCKED', blockReason: 'jump above threshold', quality: { stale: false, jumpBp: 80, missing: false } }), row({ index: 'CZEONIA', status: 'FIXED', rate: 3.5 })]))
    await renderPage(<TreasuryFixingsPage />)
    expect(await screen.findByText(/jump above threshold/)).toBeTruthy()
    expect(screen.getByText(/jump 80 bp|skok 80 bp/)).toBeTruthy()
    await click(/Run fixings|Spustit fixing/)
    const run = calls.find(c => c.url.includes('/fixings/run'))!
    expect(run.url).toMatch(/\/fixings\/run\?date=\d{4}-\d{2}-\d{2}$/)
    expect((await screen.findByRole('status')).textContent).toMatch(/2 fixed, 1 blocked|zafixováno 2, zablokováno 1/)
  })

  it('proposes a manual fixing with a reason; an empty reason is refused', async () => {
    session.roles = ['ROLE_TREASURY_BACK_OFFICE']
    session.username = 'petr.back'
    router = (url, init) => (url.includes('/manual') && init?.method === 'POST' ? json({ index: 'PRIBOR_3M', rate: 4.1, status: 'PENDING_APPROVAL' }, 201) : json([row({ status: 'BLOCKED' })]))
    await renderPage(<TreasuryFixingsPage />)
    await screen.findByText('PRIBOR_3M')
    await click(/Manual fixing PRIBOR_3M|Ruční fixing PRIBOR_3M/)
    await change(/^(Rate %|Sazba %)$/, '4.1')
    await click(/Propose fixing|Navrhnout fixing/)
    expect(await screen.findByText(/A reason is required|Důvod je povinný/)).toBeTruthy()
    await change(/Reason \(required\)|Důvod \(povinný\)/, 'feed outage')
    await click(/Propose fixing|Navrhnout fixing/)
    const post = calls.find(c => c.url.includes('/manual'))!
    expect(post.url).toMatch(/\/fixings\/PRIBOR_3M\/2026-10-19\/manual$/)
    expect(bodyOf(post)).toEqual({ rate: 4.1, reason: 'feed outage', source: 'MANUAL' })
  })

  it('an approver decides another person’s manual fixing but not their own, and not as an AI principal', async () => {
    router = (url, init) => (url.endsWith('/manual/approve') && init?.method === 'POST' ? json({ error: 'FOUR_EYES_VIOLATION', message: 'no' }, 422)
      : json([row({ status: 'PENDING_APPROVAL', proposedBy: 'petr.back', rate: 4.1 }), row({ index: 'CZEONIA', status: 'PENDING_APPROVAL', proposedBy: 'amy.approver', rate: 3.4 })]))
    await renderPage(<TreasuryFixingsPage />)
    await screen.findByText('PRIBOR_3M')
    expect(screen.getAllByRole('button', { name: /^(Approve|Schválit)$/ })).toHaveLength(1)
    expect(screen.getByText(/A different person decides|Rozhodne jiná osoba/)).toBeTruthy()
    await click(/^(Approve|Schválit)$/)
    expect(calls.find(c => c.url.endsWith('/PRIBOR_3M/2026-10-19/manual/approve'))).toBeTruthy()
    expect((await screen.findByRole('alert')).textContent).toMatch(/different person|jiná osoba/)
  })

  it('says "not yet available" when fixings are not served', async () => {
    router = () => notServed()
    await renderPage(<TreasuryFixingsPage />)
    await notAvailable()
  })
})

// ── market data ────────────────────────────────────────────────────────────────────────────────
describe('market data', () => {
  const quality = { suspicious: [{ index: 'PRIBOR_3M', date: '2026-10-19', value: 5.9, previous: 4.1, jumpBp: 180, staleDays: 0, reason: 'jump above 50 bp' }], missing: [{ index: 'ESTR', date: '2026-10-19', dealCount: 4 }] }

  it('browses factor history, suspicious factors and missing factors', async () => {
    router = url => (url.includes('/market-data/quality') ? json(quality) : url.includes('/market-data/factors') ? json([{ index: 'PRIBOR_3M', date: '2026-10-19', value: 4.1, source: 'CNB' }]) : notServed())
    await renderPage(<TreasuryMarketDataPage />)
    expect(await screen.findByText('CNB')).toBeTruthy()
    await change(/Index \/ factor|Index \/ faktor/, 'PRIBOR_3M')
    expect(new URL(calls.at(-1)!.url, 'http://x').searchParams.get('index')).toBe('PRIBOR_3M')
    await click(/Suspicious factors|Podezřelé faktory/, 'tab')
    expect(await screen.findByText('jump above 50 bp')).toBeTruthy()
    await click(/Missing factors|Chybějící faktory/, 'tab')
    expect(await screen.findByText('ESTR')).toBeTruthy()
    expect(screen.getByRole('link', { name: /4 deals|4 obchodů/ })).toBeTruthy()
  })

  it('lists internal rate sheets and back office proposes one', async () => {
    session.roles = ['ROLE_TREASURY_BACK_OFFICE']
    session.username = 'bert.back'
    router = (url, init) => (url.includes('/internal-rate-sheets') ? (init?.method === 'POST' ? json({ id: 's-2', name: 'Retail Q4', status: 'PENDING' }, 201) : json([{ id: 's-1', name: 'Retail Q3', effectiveFrom: '2026-07-01', status: 'ACTIVE', rates: [{ label: '3M', value: 3.1 }] }])) : notServed())
    await renderPage(<TreasuryMarketDataPage />)
    await click(/Internal rate sheets|Interní sazebníky/, 'tab')
    expect((await screen.findByText(/Retail Q3/)).textContent).toMatch(/3M 3\.1/)
    await change(/Rate-sheet name|Název sazebníku/, 'Retail Q4')
    await change(/Rates \(label;value|Sazby \(popisek/, '3M;3.2')
    await click(/^(Propose|Navrhnout)$/)
    expect(bodyOf(calls.find(c => c.init?.method === 'POST')!)).toMatchObject({ name: 'Retail Q4', rates: [{ label: '3M', value: 3.2 }] })
  })

  it('a different approver decides a PENDING rate sheet; its proposer and an AI principal are not offered it', async () => {
    const pending = { id: 's-9', name: 'Retail Q4', effectiveFrom: '2026-10-20', status: 'PENDING', proposedBy: 'bert.back', rates: [{ label: '3M', value: 3.2 }] }
    router = (url, init) => (url.endsWith('/internal-rate-sheets/s-9/approve') && init?.method === 'POST' ? json({ ...pending, status: 'ACTIVE' })
      : url.includes('/internal-rate-sheets') ? json([pending]) : notServed())
    session.roles = ['ROLE_TREASURY_APPROVER']
    session.username = 'amy.approver'
    await renderPage(<TreasuryMarketDataPage />)
    await click(/Internal rate sheets|Interní sazebníky/, 'tab')
    await screen.findByTestId('rate-sheet')
    await click(/^(Approve|Schválit)$/)
    expect(calls.some(c => c.url.endsWith('/internal-rate-sheets/s-9/approve') && c.init?.method === 'POST')).toBe(true)
    cleanup()
    for (const [roles, user] of [[['ROLE_TREASURY_APPROVER'], 'bert.back'], [['ROLE_TREASURY_APPROVER', 'ROLE_AI_AGENT'], 'amy.approver'], [['ROLE_TREASURY_DEALER'], 'dana.dealer']] as const) {
      session.roles = [...roles]
      session.username = user
      await renderPage(<TreasuryMarketDataPage />)
      await click(/Internal rate sheets|Interní sazebníky/, 'tab')
      await screen.findByTestId('rate-sheet')
      expect(screen.queryByRole('button', { name: /^(Approve|Schválit)$/ })).toBeNull()
      expect(screen.queryByRole('button', { name: /^(Reject|Zamítnout)$/ })).toBeNull()
      cleanup()
    }
  })

  it('imports a CSV file as rows and offers export', async () => {
    session.roles = ['ROLE_TREASURY_BACK_OFFICE']
    router = (url, init) => (url.includes('/market-data/import') && init?.method === 'POST' ? json({ imported: 1 }) : url.includes('/market-data/quality') ? json(quality) : notServed())
    await renderPage(<TreasuryMarketDataPage />)
    await click(/Import & export|Import a export/, 'tab')
    const file = new File(['index,date,value\nPRIBOR_3M,2026-10-19,4.1'], 'f.csv', { type: 'text/csv' })
    await act(async () => { fireEvent.change(screen.getByLabelText(/Import a file|Importovat soubor/), { target: { files: [file] } }) })
    await waitFor(() => expect(calls.some(c => c.url.includes('/market-data/import'))).toBe(true))
    expect(bodyOf(calls.find(c => c.url.includes('/market-data/import'))!)).toEqual([{ index: 'PRIBOR_3M', date: '2026-10-19', value: '4.1' }])
    expect(screen.getByRole('button', { name: /Export market data|Exportovat tržní data/ })).toBeTruthy()
  })

  it('says "not yet available" for each part the service does not serve', async () => {
    router = () => notServed()
    await renderPage(<TreasuryMarketDataPage />)
    await notAvailable()
  })
})

// ── payments ───────────────────────────────────────────────────────────────────────────────────
describe('payments', () => {
  const pay = (id: string, over: Record<string, unknown> = {}) => ({
    paymentId: id, status: 'PROPOSED', direction: 'OUT', counterpartyId: 'SIM-A', counterpartyName: 'Sim Bank A', currency: 'CZK', amount: 1000, valueDate: '2026-10-21',
    rail: 'CERTIS', nettedFlowIds: ['a', 'b'], dealIds: ['d-1'], createdBy: 'system', dealCreators: ['dana.dealer'], ...over,
  })

  it('lists payments by status with netting groups; release is offered to a releaser, with its own key', async () => {
    session.roles = ['ROLE_TREASURY_PAYMENT_RELEASER']
    session.username = 'rita.releaser'
    router = (url, init) => (url.endsWith('/payments/p-1/release') && init?.method === 'POST' ? json(pay('p-1', { status: 'RELEASED' })) : json([pay('p-1'), pay('p-2', { direction: 'IN', amount: 400, status: 'PROPOSED' }), pay('p-3', { status: 'SETTLED', counterpartyId: 'X', counterpartyName: 'Other' })]))
    await renderPage(<TreasuryPaymentsPage />)
    await screen.findAllByText('Sim Bank A')
    expect(screen.getByTestId('status-counts').textContent).toMatch(/Proposed: 2|Navrženo: 2/)
    expect(screen.getByTestId('netting-groups').textContent).toMatch(/2 payments|2 plateb/)
    await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: /^(Release|Uvolnit)$/ })[0]) })
    const release = calls.find(c => c.url.endsWith('/payments/p-1/release'))!
    expect(release.init?.method).toBe('POST')
    expect(keyOf(release)).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('withholds release from the creator of the deal, from a non-releaser and from an AI principal', async () => {
    router = () => json([pay('p-1')])
    session.roles = ['ROLE_TREASURY_PAYMENT_RELEASER']
    session.username = 'dana.dealer'
    await renderPage(<TreasuryPaymentsPage />)
    await screen.findByText('Sim Bank A')
    expect(screen.queryByRole('button', { name: /^(Release|Uvolnit)$/ })).toBeNull()
    expect(screen.getByText(/you created the deal|obchod jste vytvořili/)).toBeTruthy()
    cleanup()
    session.roles = ['ROLE_TREASURY_APPROVER']
    session.username = 'amy.approver'
    await renderPage(<TreasuryPaymentsPage />)
    await screen.findByText('Sim Bank A')
    expect(screen.queryByRole('button', { name: /^(Release|Uvolnit)$/ })).toBeNull()
    cleanup()
    session.roles = ['ROLE_TREASURY_PAYMENT_RELEASER', 'ROLE_AI_AGENT']
    session.username = 'rita.releaser'
    await renderPage(<TreasuryPaymentsPage />)
    await screen.findByText('Sim Bank A')
    expect(screen.queryByRole('button', { name: /^(Release|Uvolnit)$/ })).toBeNull()
  })

  it('shows the service’s refusal when a release is refused', async () => {
    session.roles = ['ROLE_TREASURY_PAYMENT_RELEASER']
    session.username = 'rita.releaser'
    router = (url, init) => (init?.method === 'POST' ? json({ error: 'FOUR_EYES_VIOLATION', message: 'creator may not release' }, 422) : json([pay('p-1')]))
    await renderPage(<TreasuryPaymentsPage />)
    await screen.findByText('Sim Bank A')
    await click(/^(Release|Uvolnit)$/)
    expect((await screen.findByRole('alert')).textContent).toMatch(/different person|jiná osoba/)
  })

  it('shows the rail reference, and offers manual reconciliation to an independent approver only', async () => {
    const sent = pay('p-9', { status: 'SENT', railReference: 'RAIL-REF-1', releasedBy: 'rita.releaser' })
    router = (url, init) => (url.endsWith('/payments/p-9/resolve') && init?.method === 'POST' ? json({ ...sent, status: 'MANUALLY_SETTLED' }) : json([sent]))
    session.roles = ['ROLE_TREASURY_APPROVER']
    session.username = 'amy.approver'
    vi.spyOn(window, 'prompt').mockReturnValue('confirmed by the correspondent')
    await renderPage(<TreasuryPaymentsPage />)
    expect((await screen.findByTestId('rail-reference')).textContent).toBe('RAIL-REF-1')
    await click(/^(Manually settled|Vypořádáno ručně)$/)
    const resolve = calls.find(c => c.url.endsWith('/payments/p-9/resolve'))!
    expect(bodyOf(resolve)).toEqual({ outcome: 'MANUALLY_SETTLED', reason: 'confirmed by the correspondent' })
    cleanup()
    // Its releaser, a term author, and an AI principal are not offered it.
    for (const [roles, user] of [[['ROLE_TREASURY_APPROVER'], 'rita.releaser'], [['ROLE_TREASURY_APPROVER'], 'dana.dealer'], [['ROLE_TREASURY_APPROVER', 'ROLE_AI_AGENT'], 'amy.approver']] as const) {
      session.roles = [...roles]
      session.username = user
      await renderPage(<TreasuryPaymentsPage />)
      await screen.findByTestId('rail-reference')
      expect(screen.queryByRole('button', { name: /^(Manually settled|Vypořádáno ručně)$/ })).toBeNull()
      expect(screen.queryByRole('button', { name: /^(Returned|Vráceno)$/ })).toBeNull()
      cleanup()
    }
  })

  it('explains SSI_CHANGED on release and reloads', async () => {
    session.roles = ['ROLE_TREASURY_PAYMENT_RELEASER']
    session.username = 'rita.releaser'
    router = (url, init) => (init?.method === 'POST' ? json({ error: 'SSI_CHANGED', message: 'ssi changed' }, 409) : json([pay('p-1')]))
    await renderPage(<TreasuryPaymentsPage />)
    await screen.findByText('Sim Bank A')
    const before = calls.length
    await click(/^(Release|Uvolnit)$/)
    expect((await screen.findByRole('alert')).textContent).toMatch(/settlement instruction changed|instrukce protistrany se změnila/)
    expect(calls.length).toBeGreaterThan(before + 1)
  })

  it('filters by status through the query string', async () => {
    router = () => json([])
    await renderPage(<TreasuryPaymentsPage />)
    await change(/^(Status|Stav)$/, 'FAILED')
    expect(new URL(calls.at(-1)!.url, 'http://x').searchParams.get('status')).toBe('FAILED')
  })

  it('says "not yet available" when payments are not served', async () => {
    router = () => notServed()
    await renderPage(<TreasuryPaymentsPage />)
    await notAvailable()
  })
})

// ── reference data ─────────────────────────────────────────────────────────────────────────────
describe('reference data', () => {
  const rec = (key: string, version: number, status: string, over: Record<string, unknown> = {}) => ({
    kind: 'calendar', key, version, status, validFrom: '2026-10-19', validTo: null, verified: false, payload: { code: key, baseCalendar: 'CERTIS', extraHolidays: [], removedHolidays: [] },
    createdBy: 'rita.editor', createdAt: '2026-10-19T08:00:00Z', approvedBy: null, reason: 'new calendar', ...over,
  })

  it('offers every kind of the catalogue and lists a kind’s records with status and the verified badge', async () => {
    router = url => (url.includes('/reference/calendar') ? json([rec('CERTIS', 2, 'ACTIVE', { verified: true }), rec('TARGET2', 1, 'ACTIVE')]) : json([]))
    await renderPage(<TreasuryReferencePage />)
    expect(await screen.findAllByText('CERTIS')).toBeTruthy()
    expect(within(screen.getByRole('navigation', { name: /kinds|druhy/i })).getAllByRole('button').length).toBe(32 + 2)
    expect(screen.getAllByText(/^(Verified|Ověřeno)$/).length).toBeGreaterThan(0)
    expect(screen.getByText(/^(Unverified|Neověřeno)$/)).toBeTruthy()
    expect(screen.getByTestId('record-count').textContent).toMatch(/2/)
    await act(async () => { fireEvent.click(within(screen.getByRole('navigation', { name: /kinds|druhy/i })).getByRole('button', { name: /Reference indices|Referenční indexy/ })) })
    expect(calls.some(c => c.url.startsWith(`${T}/reference/index`))).toBe(true)
  })

  it('shows the version timeline and what depends on a record', async () => {
    router = url => (url.includes('/reference/dependencies/') ? json({ kind: 'calendar', key: 'CERTIS', dependants: [{ type: 'index', id: 'PRIBOR_3M', field: 'fixingCalendar' }] })
      : url.endsWith('/reference/calendar/CERTIS') ? json({ kind: 'calendar', key: 'CERTIS', active: rec('CERTIS', 2, 'ACTIVE'), versions: [rec('CERTIS', 1, 'SUPERSEDED'), rec('CERTIS', 2, 'ACTIVE', { approvedBy: 'amy.approver' })] })
        : json([rec('CERTIS', 2, 'ACTIVE')]))
    await renderPage(<TreasuryReferencePage />)
    await act(async () => { fireEvent.click(await screen.findByRole('button', { name: /Open record CERTIS|Otevřít záznam CERTIS/ })) })
    const timeline = await screen.findByRole('list', { name: /Version timeline|Časová osa verzí/ })
    expect(within(timeline).getAllByRole('listitem')).toHaveLength(2)
    expect(within(timeline).getByText(/Current|Aktuální/)).toBeTruthy()
    expect(await screen.findByText(/index: PRIBOR_3M \(fixingCalendar\)/)).toBeTruthy()
  })

  it('proposes a new version from the generated form; required fields and the reason are checked first', async () => {
    session.roles = ['ROLE_TREASURY_REFERENCE_EDITOR']
    session.username = 'rita.editor'
    router = (url, init) => (url.endsWith('/reference/calendar') && init?.method === 'POST' ? json(rec('CZ-BANK', 1, 'PENDING'), 201) : json([]))
    await renderPage(<TreasuryReferencePage />)
    await click(/New record|Nový záznam/)
    await click(/Propose version|Navrhnout verzi/)
    expect((await screen.findAllByText(/Required field|Povinné pole/)).length).toBeGreaterThan(0)
    expect(screen.getByText(/A reason is required|Důvod je povinný/)).toBeTruthy()
    await change(/^(Code|Kód) \*$/, 'CZ-BANK')
    await change(/Base calendar|Základní kalendář/, 'CERTIS')
    await change(/Extra holidays|Další svátky/, '2026-12-24')
    await change(/Reason for change|Důvod změny/, 'bank holiday')
    await click(/Propose version|Navrhnout verzi/)
    const post = calls.find(c => c.url.endsWith('/reference/calendar') && c.init?.method === 'POST')!
    expect(bodyOf(post)).toMatchObject({ key: 'CZ-BANK', reason: 'bank holiday', payload: { code: 'CZ-BANK', baseCalendar: 'CERTIS', extraHolidays: ['2026-12-24'] } })
    expect(await screen.findByText(/waits? for approval|čeká na schválení|must approve it|musí schválit/i)).toBeTruthy()
  })

  it('approves and rejects another person’s pending version, but never the proposer’s own', async () => {
    router = (url, init) => (url.includes('/versions/1/approve') && init?.method === 'POST' ? json({ error: 'FOUR_EYES_VIOLATION', message: 'proposer' }, 422) : json([rec('CZ-1', 1, 'PENDING', { createdBy: 'rita.editor' }), rec('CZ-2', 1, 'PENDING', { createdBy: 'amy.approver' })]))
    await renderPage(<TreasuryReferencePage />)
    await click(/Pending queue|Ke schválení/, 'tab')
    expect(await screen.findByText('CZ-1')).toBeTruthy()
    expect(screen.getAllByRole('button', { name: /^(Approve|Schválit)$/ })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: /^(Reject|Zamítnout)$/ })).toHaveLength(1)
    expect(screen.getByText(/You proposed it|Navrhli jste/)).toBeTruthy()
    await click(/^(Approve|Schválit)$/)
    expect(calls.find(c => c.url.endsWith('/reference/calendar/CZ-1/versions/1/approve'))).toBeTruthy()
    expect((await screen.findAllByRole('alert')).map(a => a.textContent).join(' ')).toMatch(/different person|jiná osoba/)
  })

  it('an AI proposal is labelled and an AI principal gets no decision controls', async () => {
    session.roles = ['ROLE_TREASURY_APPROVER', 'ROLE_AI_AGENT']
    router = () => json([rec('CZ-1', 1, 'PENDING', { createdBy: 'agent-1', createdByType: 'AI_AGENT' })])
    await renderPage(<TreasuryReferencePage />)
    await click(/Pending queue|Ke schválení/, 'tab')
    expect(await screen.findByText(/AI proposal|AI návrh/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^(Approve|Schválit)$/ })).toBeNull()
    expect(screen.getByText(/AI principal may not approve|AI nesmí schvalovat/)).toBeTruthy()
  })

  it('exports a kind, and imports an export as proposals (reason defaulted, all-or-nothing)', async () => {
    session.roles = ['ROLE_TREASURY_BACK_OFFICE']
    session.username = 'petr.back'
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined }))
    router = (url, init) => (url.endsWith('/reference/calendar/import') && init?.method === 'POST' ? json([rec('CZ-1', 1, 'PENDING')], 201) : url.endsWith('/reference/calendar/export') ? json([rec('CZ-1', 1, 'ACTIVE')]) : json([]))
    await renderPage(<TreasuryReferencePage />)
    await click(/Import & export|Import a export/, 'tab')
    await click(/Export \(JSON\)|Exportovat \(JSON\)/)
    expect(calls.some(c => c.url.endsWith('/reference/calendar/export'))).toBe(true)
    const file = new File([JSON.stringify([rec('CZ-1', 1, 'ACTIVE')])], 'x.json', { type: 'application/json' })
    await act(async () => { fireEvent.change(screen.getByLabelText(/Import a file|Importovat soubor/), { target: { files: [file] } }) })
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/reference/calendar/import'))).toBe(true))
    expect(bodyOf(calls.find(c => c.url.endsWith('/import'))!)[0]).toMatchObject({ key: 'CZ-1', payload: { code: 'CZ-1' } })
  })

  it('shows the read-only user activity and application version history', async () => {
    router = url => (url.includes('user-activity') ? json([{ at: '2026-10-19T08:00:00Z', actor: 'rita.editor', action: 'PROPOSE', kind: 'calendar', key: 'CZ-1', version: 1 }])
      : url.includes('application-version-history') ? json([{ version: 'V12', description: 'reference data', installedOn: '2026-10-01T00:00:00Z' }]) : json([]))
    await renderPage(<TreasuryReferencePage />)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /User activity|Aktivita uživatelů/ })) })
    expect((await screen.findAllByText('rita.editor')).length).toBeGreaterThan(0)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Application version history|Historie verzí aplikace/ })) })
    expect(await screen.findByText('V12')).toBeTruthy()
  })

  it('says "not yet available" when a kind is not served', async () => {
    router = () => notServed()
    await renderPage(<TreasuryReferencePage />)
    await notAvailable()
  })
})

// ── reports ────────────────────────────────────────────────────────────────────────────────────
describe('reports', () => {
  const header = (provenance: string) => ({ runId: 'r-1', asOf: '2026-10-19', provenance, curveSetId: 'c-1', bookId: null, currency: 'CZK', valuationInputHash: 'abc', modelVersion: 'm1' })
  const base = (url: string): Response | undefined => {
    if (url.endsWith('/risk/snapshots')) return json({ runs: [{ id: 'r-1', asOf: '2026-10-19', provenance: 'synthetic', status: 'TIED_OUT' }] })
    if (url.includes('/reference/book')) return json([{ kind: 'book', key: 'BANKING', version: 1, status: 'ACTIVE', validFrom: '2026-01-01', verified: true, payload: { id: 'BANKING', name: 'Banking book' }, createdBy: 'x', createdAt: '2026-01-01T00:00:00Z' }])
    return undefined
  }

  it('lists the ten reports and computes valuation over the newest snapshot with a provenance badge', async () => {
    router = url => base(url) ?? (url.includes('/valuation') ? json({ header: header('synthetic'), deals: [{ dealId: 'd-1', pv: 100, accrued: 1, clean: 99, dirty: 100 }], book: { pv: 100, accrued: 1, clean: 99, dirty: 100 }, dataGap: null }) : notServed())
    await renderPage(<TreasuryReportsPage />)
    expect(within(screen.getByRole('group', { name: /Reports menu|Nabídka reportů/ })).getAllByRole('button')).toHaveLength(10)
    const view = await screen.findByTestId('report-view')
    expect(view.textContent).toMatch(/Synthetic|Syntetická/)
    expect(within(view).getAllByText(/Synthetic data|Simulovaná data/).length).toBeGreaterThan(0)
    expect(calls.some(c => c.url.startsWith(`${R}/snapshots/r-1/valuation`))).toBe(true)
  })

  it('runs VaR over a chosen portfolio node and renders a data gap as a gap, never as zero', async () => {
    router = url => base(url) ?? (url.includes('/var') ? json({ header: header('regulatory'), confidence: 0.99, horizonDays: 1, scenarioCount: 12, minimumScenarios: 250, valueAtRisk: null, expectedShortfall: null, dataGap: 'only 12 of 250 scenarios' }) : notServed())
    await renderPage(<TreasuryReportsPage />)
    await screen.findByRole('option', { name: /2026-10-19/ })
    await click(/Value at risk|Value at Risk/)
    await screen.findByText(/Banking book/, { selector: 'option' })
    await change(/Portfolio node|Uzel portfolia/, 'BANKING')
    await change(/Horizon|Horizont/, '5')
    const gap = await screen.findByTestId('data-gap')
    expect(gap.textContent).toMatch(/only 12 of 250 scenarios/)
    expect(screen.getByTestId('var-figures').textContent).toMatch(/no figure|bez čísla/)
    expect(screen.getByTestId('var-figures').textContent).not.toMatch(/\b0[,.]00\b/)
    expect(screen.getByTestId('report-view').textContent).toMatch(/Regulatory|Regulatorní/)
    const varCall = calls.filter(c => c.url.includes('/var')).pop()!
    const q = new URL(varCall.url, 'http://x').searchParams
    expect(Object.fromEntries(q)).toMatchObject({ bookId: 'BANKING', horizonDays: '5', confidence: '0.99' })
  })

  it('shows sensitivity DV01 buckets and payment orders with the intraday default', async () => {
    router = url => base(url) ?? (url.includes('/sensitivity') ? json({ header: header('intraday'), bucketSetId: 'irrbb-standard', pv01: -88.1, dv01Buckets: [{ bucket: '1Y', dv01: -50 }], fxDelta: [], dataGap: null })
      : url.endsWith('/treasury/payments') ? json([{ paymentId: 'p-1', status: 'PROPOSED' }]) : notServed())
    await renderPage(<TreasuryReportsPage />)
    await screen.findByRole('option', { name: /2026-10-19/ })
    await click(/Sensitivity|Citlivost/)
    expect(await screen.findByText('1Y')).toBeTruthy()
    expect(screen.getByTestId('report-view').textContent).toMatch(/Intraday|Vnitrodenní/)
    await click(/Payment orders|Platební příkazy/)
    await waitFor(() => expect(screen.getByTestId('report-view').textContent).toMatch(/PROPOSED/))
    expect(screen.getByTestId('report-view').textContent).toMatch(/Intraday|Vnitrodenní/)
  })

  it('says "not yet available" for a report the deployment does not serve', async () => {
    router = url => base(url) ?? notServed()
    await renderPage(<TreasuryReportsPage />)
    await notAvailable()
  })
})

// ── day completion ─────────────────────────────────────────────────────────────────────────────
describe('day completion', () => {
  it('shows each step with its status and count, and reloads for another date', async () => {
    router = url => (url.includes('/revaluations') ? json([]) : json({ date: '2026-10-19', steps: [
      { step: 'FIXINGS', status: 'DONE', count: 4, detail: 'all fixed', at: '2026-10-19T17:00:00Z' },
      { step: 'ACCRUALS', status: 'DONE', count: 12, detail: null, at: '2026-10-19T17:05:00Z' },
      { step: 'PAYMENTS', status: 'FAILED', count: 1, detail: 'rail unavailable', at: null },
      { step: 'REVALUATION', status: 'PENDING', count: null, detail: null, at: null },
      { step: 'CORRECTIONS', status: 'SKIPPED', count: 0, detail: null, at: null },
      { step: 'MATURITIES', status: 'DONE', count: 2, detail: null, at: '2026-10-19T17:10:00Z' },
    ] }))
    await renderPage(<TreasuryDayCompletionPage />)
    const table = await screen.findByRole('table')
    expect(within(table).getAllByRole('row')).toHaveLength(7)
    expect(within(table).getByText('rail unavailable')).toBeTruthy()
    expect(within(table).getByText(/^(Failed|Selhalo)$/)).toBeTruthy()
    expect(within(table).getByText(/^(Pending|Čeká)$/)).toBeTruthy()
    await change(/^(Day|Den)$/, '2026-10-16')
    expect(calls.some(c => c.url === `${T}/day-completion/2026-10-16`)).toBe(true)
    expect(calls.some(c => c.url === `${T}/revaluations?asOf=2026-10-16`)).toBe(true)
  })

  it('lists the day’s revaluations, and lets back office re-run it showing each data gap', async () => {
    session.roles = ['ROLE_TREASURY_BACK_OFFICE']
    session.username = 'petr.back'
    const rev = { dealId: 'd-7', asOf: '2026-10-19', snapshotId: 's-1', fairValue: 125000, carrying: 0, cumulative: 125000, change: 125000, journalId: 'j-1' }
    router = (url, init) => (url.includes('/revaluations/run') && init?.method === 'POST'
      ? json({ asOf: '2026-10-19', snapshotId: 's-1', revalued: [rev], journals: 0, reposted: 0, owed: 0, gaps: [{ dealId: 'd-8', reason: 'unpriced by the risk engine: no curve' }], failures: [] })
      : url.includes('/revaluations') ? json([rev])
        : json({ date: '2026-10-19', steps: [{ step: 'REVALUATION', status: 'FAILED', count: 1, detail: '1 deal(s) not revalued', at: null }] }))
    await renderPage(<TreasuryDayCompletionPage />)
    expect(within(await screen.findByTestId('revaluation-table')).getByText('d-7')).toBeTruthy()
    await click(/Run revaluation|Spustit přecenění/)
    expect(calls.some(c => c.url.includes('/revaluations/run?asOf=') && c.init?.method === 'POST')).toBe(true)
    expect(screen.getByTestId('revaluation-gaps').textContent).toMatch(/d-8: unpriced/)
  })

  it('offers no revaluation run to a dealer', async () => {
    session.roles = ['ROLE_TREASURY_DEALER']
    router = url => (url.includes('/revaluations') ? json([]) : json({ date: '2026-10-19', steps: [] }))
    await renderPage(<TreasuryDayCompletionPage />)
    await screen.findByText(/No revaluation recorded|není zaznamenáno žádné přecenění/)
    expect(screen.queryByRole('button', { name: /Run revaluation|Spustit přecenění/ })).toBeNull()
  })

  it('says "not yet available" when day completion is not served', async () => {
    router = url => (url.includes('/revaluations') ? json([]) : notServed())
    await renderPage(<TreasuryDayCompletionPage />)
    await notAvailable()
  })
})
