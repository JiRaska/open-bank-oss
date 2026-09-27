// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Nostro reconciliation page (ADR-0315 D7, #10896): the upload form (APPROVER only, mirroring
// NostroResource's method-level @RolesAllowed) sends the camt.053 body with a fresh Idempotency-Key
// per submit and renders the server's 400/409 refusal readably; the result view must never render a
// null difference as a zero, and must render an empty unmatched list as "none", not a blank table.

import React, { Suspense } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const session = vi.hoisted(() => ({ roles: ['ROLE_TREASURY_APPROVER'] as string[], username: 'anna.approver' }))
const jwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'Anna', roles: session.roles, accessToken: jwt({ preferred_username: session.username, sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))

import NostroReconciliationPage from '@/app/treasury/nostro/page'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const STATEMENT = {
  id: 'a1111111-1111-1111-1111-111111111111', statementId: 'SYNTH-STMT-1', iban: 'CZ1299990000000000001001',
  glCode: '1001', currency: 'CZK', statementDate: '2026-09-25', openingBalance: 1000000, closingBalance: 1155000,
  entryCount: 3, sha256: 'abc', uploadedBy: 'anna.approver', uploadedAt: '2026-09-25T08:00:00Z',
}

const entry = (sequence: number, reference: string | null = `SYNTH-${sequence}`) => ({
  sequence, amount: 1000, currency: 'CZK', direction: 'CRDT', bookingDate: '2026-09-25', reference,
})
const ledgerLine = (lineId: string, description: string | null = 'treasury deal matured') => ({
  journalId: 'j-1', lineId, transactionId: 't-1', entryDate: '2026-09-25', side: 'DEBIT', amount: 1000, currency: 'CZK', description,
})

const reconciliation = (overrides: Partial<Record<string, unknown>> = {}) => ({
  statementUuid: STATEMENT.id, statementId: STATEMENT.statementId, iban: STATEMENT.iban, glCode: '1001',
  currency: 'CZK', statementDate: '2026-09-25',
  statementOpeningBalance: 1000000, ledgerOpeningBalance: 1000000, openingDifference: 0,
  statementClosingBalance: 1155000, ledgerClosingBalance: 1149958, closingDifference: 5042,
  reconciled: false,
  matches: [{ matchType: 'EXACT', entry: entry(1), ledgerLine: ledgerLine('l-1') }],
  unmatchedStatementEntries: [entry(3, 'SYNTH-SVCR-0003')],
  unmatchedLedgerLines: [ledgerLine('l-2')],
  ...overrides,
})

const renderPage = async (node: React.ReactNode) => {
  await act(async () => { render(<LanguageProvider><Suspense fallback={null}>{node}</Suspense></LanguageProvider>) })
}

const xmlFile = () => new File(['<Document/>'], 'statement.xml', { type: 'application/xml' })

const selectFile = (input: HTMLElement, file: File) => {
  Object.defineProperty(input, 'files', { value: [file], configurable: true })
  fireEvent.change(input)
}

let calls: { url: string; init?: RequestInit }[] = []
let router: (url: string, init?: RequestInit) => Response

beforeEach(() => {
  calls = []
  session.roles = ['ROLE_TREASURY_APPROVER']
  session.username = 'anna.approver'
  vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => { calls.push({ url: String(u), init }); return router(String(u), init) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('nostro upload — approver only', () => {
  it('shows the upload form to an approver', async () => {
    router = () => json({}, 404)
    await renderPage(<NostroReconciliationPage />)
    expect(screen.getByLabelText(/Choose statement file|Vybrat soubor výpisu/)).toBeInTheDocument()
  })

  it('hides the upload form from a dealer, who can still read', async () => {
    session.roles = ['ROLE_TREASURY_DEALER']
    router = () => json({}, 404)
    await renderPage(<NostroReconciliationPage />)
    expect(screen.queryByLabelText(/Choose statement file|Vybrat soubor výpisu/)).toBeNull()
    expect(screen.getByLabelText(/Statement ID|ID výpisu/)).toBeInTheDocument()
  })

  it('sends the XML body with content-type application/xml and a fresh Idempotency-Key, then loads the reconciliation', async () => {
    router = (url, init) => {
      if (url.endsWith('/api/v1/treasury/nostro/statements') && init?.method === 'POST') return json(STATEMENT, 201)
      if (url.includes(`/nostro/statements/${STATEMENT.id}/reconciliation`)) return json(reconciliation())
      return json({}, 404)
    }
    await renderPage(<NostroReconciliationPage />)
    selectFile(screen.getByLabelText(/Choose statement file|Vybrat soubor výpisu/), xmlFile())
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Upload statement|Nahrát výpis/ })) })
    await screen.findByText(/SYNTH-STMT-1/)

    const post = calls.find(c => c.init?.method === 'POST')!
    expect(post.url).toBe('/api/svc/treasury-service/api/v1/treasury/nostro/statements')
    expect(new Headers(post.init!.headers).get('content-type')).toBe('application/xml')
    const key = new Headers(post.init!.headers).get('idempotency-key')
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('renders a 400 malformed-statement refusal readably', async () => {
    router = (url, init) => (url.endsWith('/statements') && init?.method === 'POST'
      ? json({ error: 'INVALID_STATEMENT', message: 'a statement that does not foot' }, 400)
      : json({}, 404))
    await renderPage(<NostroReconciliationPage />)
    selectFile(screen.getByLabelText(/Choose statement file|Vybrat soubor výpisu/), xmlFile())
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Upload statement|Nahrát výpis/ })) })
    const status = await screen.findByRole('status')
    expect(status.textContent).toContain('a statement that does not foot')
  })

  it('renders a 409 duplicate-statement refusal readably', async () => {
    router = (url, init) => (url.endsWith('/statements') && init?.method === 'POST'
      ? json({ error: 'DUPLICATE_STATEMENT', message: 'already uploaded under a different key' }, 409)
      : json({}, 404))
    await renderPage(<NostroReconciliationPage />)
    selectFile(screen.getByLabelText(/Choose statement file|Vybrat soubor výpisu/), xmlFile())
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Upload statement|Nahrát výpis/ })) })
    const status = await screen.findByRole('status')
    expect(status.textContent).toContain('already uploaded under a different key')
  })
})

describe('reconciliation result rendering', () => {
  beforeEach(() => {
    session.roles = ['ROLE_TREASURY_DEALER']
  })

  it('highlights a non-zero difference and shows matched/unmatched rows', async () => {
    router = url => (url.includes('/reconciliation') ? json(reconciliation()) : json({}, 404))
    await renderPage(<NostroReconciliationPage />)
    fireEvent.change(screen.getByLabelText(/Statement ID|ID výpisu/), { target: { value: STATEMENT.id } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /^(Load|Načíst)$/ })) })
    await screen.findByText(/SYNTH-STMT-1/)

    expect(screen.getByText(/Not reconciled|Nesouhlasí/)).toBeInTheDocument()
    expect(screen.getByText('SYNTH-SVCR-0003')).toBeInTheDocument()
    expect(screen.getAllByText('t-1').length).toBeGreaterThan(0)
    // The closing difference (5042, non-zero) must render as a number, not be hidden or zeroed.
    expect(screen.getAllByText(/5[\s,. ]?042/).length).toBeGreaterThan(0)
  })

  it('never renders a null difference as 0, and renders empty unmatched lists as empty, not zero rows', async () => {
    router = url => (url.includes('/reconciliation')
      ? json(reconciliation({ openingDifference: null, closingDifference: null, unmatchedStatementEntries: [], unmatchedLedgerLines: [], reconciled: true }))
      : json({}, 404))
    await renderPage(<NostroReconciliationPage />)
    fireEvent.change(screen.getByLabelText(/Statement ID|ID výpisu/), { target: { value: STATEMENT.id } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /^(Load|Načíst)$/ })) })
    await screen.findByText(/SYNTH-STMT-1/)

    // "not computed" must appear for BOTH null differences, and no literal 0/0.00 difference tile.
    expect(screen.getAllByText(/not computed|nevypočteno/).length).toBe(2)
    expect(screen.queryByText(/^0[.,]00 CZK$/)).toBeNull()
    expect(screen.getAllByText(/No unmatched statement entries|Žádné nespárované položky výpisu/).length).toBe(1)
    expect(screen.getAllByText(/No unmatched ledger lines|Žádné nespárované položky hlavní knihy/).length).toBe(1)
    expect(screen.getByText(/Reconciled|Sesouhlaseno/)).toBeInTheDocument()
  })

  it('shows a 404 for an unknown statement id readably', async () => {
    router = () => json({}, 404)
    await renderPage(<NostroReconciliationPage />)
    fireEvent.change(screen.getByLabelText(/Statement ID|ID výpisu/), { target: { value: 'does-not-exist' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /^(Load|Načíst)$/ })) })
    const status = await screen.findByRole('status')
    expect(status.textContent).toMatch(/No such statement|Výpis nebyl nalezen/)
  })
})
