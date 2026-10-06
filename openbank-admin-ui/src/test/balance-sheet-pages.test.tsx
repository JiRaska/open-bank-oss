// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React, { Suspense } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const session = vi.hoisted(() => ({ roles: ['ROLE_FINANCE'] as string[], username: 'jana.finance' }))
const jwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'Jana', roles: session.roles, accessToken: jwt({ preferred_username: session.username, sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))

vi.mock('recharts', () => {
  const Pass = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>
  const Nil = () => null
  return {
    ResponsiveContainer: Pass, ComposedChart: Pass, Bar: Nil, Line: Nil, XAxis: Nil, YAxis: Nil,
    CartesianGrid: Nil, Tooltip: Nil, Legend: Nil, ReferenceLine: Nil,
  }
})

import LedgerBackfillPage from '@/app/balance-sheet/ledger-backfill/page'
import LedgerBackfillVoidPage from '@/app/balance-sheet/ledger-backfill/voids/page'
import SnapshotDetailPage from '@/app/balance-sheet/snapshots/[id]/page'
import SnapshotCapitalPage from '@/app/balance-sheet/snapshots/[id]/capital/page'
import SnapshotIrrbbPage from '@/app/balance-sheet/snapshots/[id]/irrbb/page'
import SnapshotLiquidityPage from '@/app/balance-sheet/snapshots/[id]/liquidity/page'
import SnapshotLiquidityForecastPage from '@/app/balance-sheet/snapshots/[id]/liquidity-forecast/page'
import SnapshotMinReservesPage from '@/app/balance-sheet/snapshots/[id]/min-reserves/page'
import SnapshotsPage from '@/app/balance-sheet/snapshots/page'
import { bankToday } from '@/components/balance-sheet/model'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const REQUEST = {
  cutoverDate: '2026-09-25', planHash: 'h', loanCount: 2, legCount: 13, decidedBy: null, decisionReason: null,
  executedBy: null, proposedAt: '2026-09-25T08:00:00Z', decidedAt: null, executedAt: null,
}
const OWN = { ...REQUEST, id: 'own-1', state: 'PROPOSED', proposedBy: 'jana.finance' }
const OTHER = { ...REQUEST, id: 'other-1', state: 'PROPOSED', proposedBy: 'petr.finance' }
const APPROVED = { ...REQUEST, id: 'appr-1', state: 'APPROVED', proposedBy: 'jana.finance', decidedBy: 'petr.finance' }

const renderPage = async (node: React.ReactNode) => {
  await act(async () => { render(<LanguageProvider><Suspense fallback={null}>{node}</Suspense></LanguageProvider>) })
}

let calls: { url: string; init?: RequestInit }[] = []
let router: (url: string, init?: RequestInit) => Response

beforeEach(() => {
  calls = []
  session.roles = ['ROLE_FINANCE']
  session.username = 'jana.finance'
  vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => { calls.push({ url: String(u), init }); return router(String(u), init) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('ledger backfill — four-eyes in the console', () => {
  beforeEach(() => {
    router = (url, init) => {
      if (url.includes('/ledger-backfill/requests/other-1/decide')) return json({ error: 'Maker and checker must differ' }, 422)
      if (url.includes('/ledger-backfill/requests') && !init?.method) return json({ requests: [OWN, OTHER, APPROVED] })
      return json({}, 404)
    }
  })

  it('goes through the lending BFF path, never a direct service URL', async () => {
    await renderPage(<LedgerBackfillPage />)
    await screen.findAllByText('petr.finance')
    expect(calls[0].url).toBe('/api/svc/lending-service/api/v1/lending/ledger-backfill/requests?limit=25')
  })

  it('hides approve from the proposer and offers it on a colleague’s proposal', async () => {
    await renderPage(<LedgerBackfillPage />)
    await screen.findByText(/You proposed this|Tento návrh je váš/)
    // two PROPOSED rows, one of them own → exactly one approve control
    expect(screen.getAllByRole('button', { name: /^(Schválit|Approve)$/ })).toHaveLength(1)
  })

  it('shows the backend 422 when the server refuses under four-eyes', async () => {
    await renderPage(<LedgerBackfillPage />)
    fireEvent.click(await screen.findByRole('button', { name: /^(Schválit|Approve)$/ }))
    await screen.findByText(/čtyř očí|four-eyes rule/)
    expect(screen.getByText(/Maker and checker must differ/)).toBeTruthy()
    const decide = calls.find(c => c.url.includes('/decide'))!
    expect(decide.init?.method).toBe('POST')
    expect(JSON.parse(String(decide.init?.body))).toEqual({ approve: true, reason: null })
  })

  it('executes only after explicit confirmation, with execute=true', async () => {
    router = (url, init) => {
      if (url.includes('/requests/appr-1/execute')) {
        return json({ execution: { requestId: 'appr-1', executed: true, complete: true, loans: [{ loanId: 'l1', status: 'POSTED', legsPosted: 9, legsTotal: 9 }] }, glTotals: [] })
      }
      if (url.includes('/ledger-backfill/requests') && !init?.method) return json({ requests: [APPROVED] })
      return json({}, 404)
    }
    await renderPage(<LedgerBackfillPage />)
    fireEvent.click(await screen.findByRole('button', { name: /Provést|Execute/ }))
    const post = screen.getByRole('button', { name: /Zaúčtovat|Post journals/ }) as HTMLButtonElement
    expect(post.disabled).toBe(true)
    expect(calls.some(c => c.url.includes('/execute'))).toBe(false)
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(post)
    await screen.findByText(/Backfill complete|Doúčtování dokončeno/)
    expect(calls.find(c => c.url.includes('/execute'))!.url).toContain('/requests/appr-1/execute?execute=true')
  })
})

describe('ledger backfill void — four-eyes in the console (#10969, #11487)', () => {
  const SOURCE = { ...REQUEST, id: 'src-1', state: 'EXECUTED', proposedBy: 'petr.finance', executedBy: 'petr.finance', executedAt: '2026-09-26T07:48:34Z' }
  const VOID = {
    sourceRequestId: 'src-1', voidDate: '2026-09-30', planHash: 'v', loanCount: 44, legCount: 352,
    decidedBy: null, decisionReason: null, executedBy: null, lastResult: null, proposedAt: '2026-09-30T08:00:00Z',
  }
  const V_OWN = { ...VOID, id: 'v-own', state: 'PROPOSED', proposedBy: 'jana.finance' }
  const V_OTHER = { ...VOID, id: 'v-other', state: 'PROPOSED', proposedBy: 'petr.finance' }
  const V_APPROVED = { ...VOID, id: 'v-appr', state: 'APPROVED', proposedBy: 'petr.finance', decidedBy: 'jana.finance' }
  const PLAN = {
    plan: { cutoverDate: '2026-09-30', planHash: 'v', tieOut: [], loans: [{ loanId: 'l1', currency: 'CZK', status: 'ACTIVE', unpaidPrincipal: 1, legs: [] }] },
    executable: true, journalCount: 352, glTotals: [{ code: '1200', currency: 'CZK', debit: 1, credit: 0, net: 1 }],
  }
  beforeEach(() => {
    router = (url, init) => {
      if (url.includes('/voids/plan')) return json(PLAN)
      if (url.includes('/voids/v-other/decide')) return json({ error: 'Maker and checker must differ' }, 422)
      if (url.includes('/voids/v-appr/execute')) {
        return json({ execution: { requestId: 'v-appr', executed: true, complete: true, loans: [{ loanId: 'l1', status: 'VOIDED', legs: [] }] }, offsetGlTotals: [] })
      }
      if (url.endsWith('/ledger-backfill/voids') && init?.method === 'POST') return json({ ...V_OWN }, 201)
      if (url.includes('/ledger-backfill/voids') && !init?.method) return json({ requests: [V_OWN, V_OTHER, V_APPROVED] })
      if (url.includes('/ledger-backfill/requests') && !init?.method) return json({ requests: [SOURCE, APPROVED] })
      return json({}, 404)
    }
  })

  it('lists voids through the lending BFF and offers only EXECUTED backfills as sources', async () => {
    await renderPage(<LedgerBackfillVoidPage />)
    await screen.findAllByText('petr.finance')
    expect(calls.some(c => c.url === '/api/svc/lending-service/api/v1/lending/ledger-backfill/voids?limit=25')).toBe(true)
    const options = screen.getAllByRole('option').map(o => (o as HTMLOptionElement).value)
    expect(options).toEqual(['src-1'])
  })

  it('reads like a finance document: no raw state enums, no truncated ids, styled table headers', async () => {
    await renderPage(<LedgerBackfillVoidPage />)
    await screen.findAllByText('petr.finance')
    expect(screen.getAllByText(/Čeká na schválení|Awaiting approval/).length).toBe(2)
    expect(screen.queryByText(/^(PROPOSED|APPROVED|EXECUTED|UNWOUND)$/)).toBeNull()
    expect(document.body.textContent).not.toMatch(/#\d{4,}/)
    expect(document.body.textContent).not.toContain('UNWOUND')
    const option = screen.getAllByRole('option')[0]
    expect(option.textContent).toMatch(/2 (úvěrů|loans), 13 (zápisů|entries)/)
    expect(option.textContent).not.toContain('src-1')
    const headers = screen.getAllByRole('columnheader').map(h => h.textContent)
    expect(headers).toEqual(expect.arrayContaining([expect.stringMatching(/Průběh schválení|Approval trail/)]))
    expect(screen.getAllByRole('table').every(tb => tb.className.includes('data-table'))).toBe(true)
  })

  it('states the dry-run as a plain summary with the total amount', async () => {
    await renderPage(<LedgerBackfillVoidPage />)
    await screen.findAllByRole('option')
    fireEvent.click(screen.getByRole('button', { name: /Spočítat plán|Compute plan/ }))
    const note = await screen.findByRole('note')
    expect(note.textContent?.replace(/[  ]/g, ' ')).toMatch(/(Storno vrátí 1 úvěrů a vytvoří 352 protizápisů v celkové výši 1,00 Kč|reverses 1 loans and creates 352 offsetting entries totalling CZK 1\.00)/)
  })

  it('dry-runs the chosen source and proposes with an Idempotency-Key', async () => {
    await renderPage(<LedgerBackfillVoidPage />)
    await screen.findAllByRole('option')
    fireEvent.click(screen.getByRole('button', { name: /Spočítat plán|Compute plan/ }))
    fireEvent.click(await screen.findByRole('button', { name: /Navrhnout storno|Propose void/ }))
    await screen.findByText(/Void proposal recorded|Návrh storna zaznamenán/)
    expect(calls.find(c => c.url.includes('/voids/plan'))!.url).toContain('sourceRequestId=src-1')
    const post = calls.find(c => c.url.endsWith('/ledger-backfill/voids') && c.init?.method === 'POST')!
    expect(JSON.parse(String(post.init?.body))).toEqual({ sourceRequestId: 'src-1' })
    expect((post.init?.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy()
  })

  it('hides approve from the proposer and shows the backend 422 on a refused decision', async () => {
    await renderPage(<LedgerBackfillVoidPage />)
    await screen.findByText(/You proposed this|Tento návrh je váš/)
    const approve = screen.getAllByRole('button', { name: /^(Schválit|Approve)$/ })
    expect(approve).toHaveLength(1)
    fireEvent.click(approve[0])
    await screen.findByText(/Maker and checker must differ/)
    const decide = calls.find(c => c.url.includes('/voids/v-other/decide'))!
    expect((decide.init?.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy()
  })

  it('executes only after explicit confirmation, with execute=true', async () => {
    await renderPage(<LedgerBackfillVoidPage />)
    fireEvent.click(await screen.findByRole('button', { name: /Provést|Execute/ }))
    const run = screen.getByRole('button', { name: /^(Stornovat|Void loans)$/ }) as HTMLButtonElement
    expect(run.disabled).toBe(true)
    expect(calls.some(c => c.url.includes('/execute'))).toBe(false)
    fireEvent.click(screen.getAllByRole('checkbox')[0])
    fireEvent.click(run)
    await screen.findByText(/Void complete|Storno dokončeno/)
    expect(calls.find(c => c.url.includes('/execute'))!.url).toContain('/voids/v-appr/execute?execute=true')
  })
})

describe('snapshots', () => {
  it('finance sees the run list but not the create form', async () => {
    router = () => json({ runs: [{ id: 'run-1', asOf: '2026-09-30', recordedAt: '2026-09-30T06:00:00Z', provenance: 'synthetic', status: 'UNTIED', positionCount: 3, mismatchCount: 1 }] })
    await renderPage(<SnapshotsPage />)
    await screen.findByText('2026-09-30')
    expect(screen.getByText(/Synthetic data|Syntetická data/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Build snapshot|Sestavit snímek/ })).toBeNull()
    expect(calls[0].url).toBe('/api/svc/risk-engine/api/v1/risk/snapshots?limit=25')
  })

  it('risk sees the create form', async () => {
    session.roles = ['ROLE_RISK']
    router = () => json({ runs: [] })
    await renderPage(<SnapshotsPage />)
    expect(await screen.findByRole('button', { name: /Build snapshot|Sestavit snímek/ })).toBeTruthy()
  })

  it('blocks a future as-of (max = Prague today, hint, no request) and allows today', async () => {
    session.roles = ['ROLE_RISK']
    router = () => json({ runs: [] })
    await renderPage(<SnapshotsPage />)
    const input = (await screen.findByLabelText(/Datum snímku|Snapshot as-of date/)) as HTMLInputElement
    const btn = screen.getByRole('button', { name: /Build snapshot|Sestavit snímek/ }) as HTMLButtonElement
    const today = bankToday()
    expect(input.max).toBe(today)
    fireEvent.change(input, { target: { value: '9999-12-31' } })
    expect(btn.disabled).toBe(true)
    expect(screen.getByRole('alert').textContent).toMatch(/v budoucnosti|in the future/)
    fireEvent.click(btn)
    expect(calls.some(c => c.init?.method === 'POST')).toBe(false)
    fireEvent.change(input, { target: { value: today } })
    expect(btn.disabled).toBe(false)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('surfaces the server 400 message when the engine refuses the as-of', async () => {
    session.roles = ['ROLE_RISK']
    router = (_url, init) => init?.method === 'POST'
      ? json({ error: "field 'asOf' must not be after the current business date (2026-10-01)" }, 400)
      : json({ runs: [] })
    await renderPage(<SnapshotsPage />)
    fireEvent.change(await screen.findByLabelText(/Datum snímku|Snapshot as-of date/), { target: { value: bankToday() } })
    fireEvent.click(screen.getByRole('button', { name: /Build snapshot|Sestavit snímek/ }))
    expect(await screen.findByText(/must not be after the current business date/)).toBeTruthy()
  })

  it('shows a human requester as-is, a system: one as a scheduled-run badge, and — for a null/missing one', async () => {
    router = () => json({
      runs: [
        { id: 'run-human', asOf: '2026-09-30', recordedAt: '2026-09-30T06:00:00Z', provenance: 'production', status: 'TIED_OUT', positionCount: 1, mismatchCount: 0, requestedBy: 'jana.finance' },
        { id: 'run-system', asOf: '2026-09-29', recordedAt: '2026-09-29T06:00:00Z', provenance: 'production', status: 'TIED_OUT', positionCount: 1, mismatchCount: 0, requestedBy: 'system:risk-engine-eod-snapshot' },
        { id: 'run-null', asOf: '2026-09-28', recordedAt: '2026-09-28T06:00:00Z', provenance: 'production', status: 'TIED_OUT', positionCount: 1, mismatchCount: 0, requestedBy: null },
        { id: 'run-missing', asOf: '2026-09-27', recordedAt: '2026-09-27T06:00:00Z', provenance: 'production', status: 'TIED_OUT', positionCount: 1, mismatchCount: 0 },
      ],
    })
    await renderPage(<SnapshotsPage />)
    await screen.findByText('jana.finance')
    expect(screen.getByText(/Scheduled run \(risk-engine-eod-snapshot\)|Plánovaný běh \(risk-engine-eod-snapshot\)/)).toBeTruthy()
    const dashes = screen.getAllByTitle(/nezaznamenáno|not recorded/)
    expect(dashes).toHaveLength(2)
  })

  it('an UNTIED run shows its mismatches and fetches nothing derived from it', async () => {
    router = () => json({
      id: 'run-1', asOf: '2026-09-30', recordedAt: '2026-09-30T06:00:00Z', provenance: 'production', status: 'UNTIED',
      positionCount: 3, mismatchCount: 1, inputHash: 'abcdef0123456789',
      mismatches: [{ glAccountCode: '2100', currency: 'CZK', ledgerNet: -1000, positionsNet: 0, difference: -1000 }],
    })
    await renderPage(<SnapshotDetailPage params={Promise.resolve({ id: 'run-1' })} />)
    await screen.findByText('2100')
    expect(calls.map(c => c.url)).toEqual(['/api/svc/risk-engine/api/v1/risk/snapshots/run-1'])
  })

  it('a TIED_OUT run states unpriced currencies and unexpanded positions instead of drawing zeros', async () => {
    router = url => {
      if (url.includes('/instruments')) return json({ runId: 'run-2', asOf: '2026-09-30', instruments: [] })
      if (url.includes('/curve-sets')) return json({ curveSets: [{ id: 'cs-1', asOf: '2026-09-30', provenance: 'synthetic', source: 'desk', recordedAt: '2026-09-30T06:00:00Z', indices: ['CZEONIA'] }] })
      if (url.includes('/cash-flows')) {
        return json({
          runId: 'run-2', asOf: '2026-09-30', provenance: 'production', curveSetId: 'cs-1', curveSetProvenance: 'synthetic',
          model: { id: 'nmd', version: '1', coreRatio: 0.6, coreRunoffYears: 5, annualDepositRate: 0 },
          expandedPositions: 3, notExpanded: 2, notExpandedReason: 'GL_ACCOUNT positions carry no contract terms',
          currencies: [{ currency: 'CZK', discountIndex: 'CZEONIA', positions: 3, priced: true, buckets: [{ bucket: 'overnight', amount: -300 }], total: -300, presentValue: -299.5 }],
          unpriced: ['USD'],
        })
      }
      return json({ id: 'run-2', asOf: '2026-09-30', recordedAt: '2026-09-30T06:00:00Z', provenance: 'production', status: 'TIED_OUT', positionCount: 3, mismatchCount: 0, inputHash: 'abcdef0123456789', mismatches: [] })
    }
    await renderPage(<SnapshotDetailPage params={Promise.resolve({ id: 'run-2' })} />)
    await screen.findByText(/Not expanded: 2|Nerozpadnuto: 2/)
    expect(screen.getByText(/USD/)).toBeTruthy()
    expect(screen.getByText(/GL_ACCOUNT positions carry no contract terms/)).toBeTruthy()
    expect(calls.some(c => c.url.includes('/cash-flows?curveSetId=cs-1'))).toBe(true)
    expect(document.querySelector('a[href="/balance-sheet/snapshots/run-2/capital"]')).not.toBeNull()
  })

  it('shows a badge per limit status, and a NOT_EVALUABLE limit with its reason and no figure', async () => {
    const limit = (limitId: string, status: string, value: number | null, extra: Record<string, unknown> = {}) => ({
      limitId, metric: limitId, metricDescription: `${limitId} description`, bound: 'MIN', limit: 1, earlyWarning: 1.1,
      status, value, basis: value === null ? null : `${limitId} basis`, reason: value === null ? `${limitId} gap reason` : null,
      citation: 'CRR', ...extra,
    })
    router = url => {
      if (url.includes('/limits')) {
        return json({
          runId: 'run-4', asOf: '2026-09-30', provenance: 'synthetic',
          limitSet: { id: 'openbank-risk-appetite', version: '1', source: 'src' }, curveSetId: null,
          limits: [
            limit('lcr-min', 'BREACH', 0.5),
            limit('nsfr-min', 'EARLY_WARNING', 1.02),
            limit('total-capital-ratio-min', 'OK', 0.44),
            limit('irrbb-eve-outlier', 'NOT_EVALUABLE', null, { bound: 'MAX', limit: 0.15, earlyWarning: 0.12 }),
          ],
          summary: { OK: 1, EARLY_WARNING: 1, BREACH: 1, NOT_EVALUABLE: 1 }, notes: [],
        })
      }
      if (url.includes('/instruments')) return json({ runId: 'run-4', asOf: '2026-09-30', instruments: [] })
      if (url.includes('/curve-sets')) return json({ curveSets: [] })
      return json({ id: 'run-4', asOf: '2026-09-30', recordedAt: '2026-09-30T06:00:00Z', provenance: 'synthetic', status: 'TIED_OUT', positionCount: 3, mismatchCount: 0, inputHash: 'abcdef0123456789', mismatches: [] })
    }
    await renderPage(<SnapshotDetailPage params={Promise.resolve({ id: 'run-4' })} />)
    await screen.findByText('lcr-min')
    expect(calls.some(c => c.url === '/api/svc/risk-engine/api/v1/risk/snapshots/run-4/limits')).toBe(true)
    const row = (id: string) => screen.getByText(id).closest('tr') as HTMLElement
    expect(row('lcr-min').textContent).toMatch(/Breach|Překročeno/)
    expect(row('lcr-min').querySelector('.badge-danger')).not.toBeNull()
    expect(row('nsfr-min').querySelector('.badge-warning')).not.toBeNull()
    expect(row('total-capital-ratio-min').querySelector('.badge-success')).not.toBeNull()
    const gap = row('irrbb-eve-outlier')
    expect(gap.textContent).toMatch(/Not evaluable|Nelze vyhodnotit/)
    expect(gap.textContent).toContain('irrbb-eve-outlier gap reason')
    expect(gap.querySelector('.badge-success')).toBeNull() // a gap is never green
    expect(gap.textContent).not.toMatch(/0 %/)
  })

  it('a limits read that fails is shown as unavailable, never as all-clear', async () => {
    router = url => {
      if (url.includes('/limits')) return json({ error: 'boom' }, 500)
      if (url.includes('/instruments')) return json({ runId: 'run-5', asOf: '2026-09-30', instruments: [] })
      if (url.includes('/curve-sets')) return json({ curveSets: [] })
      return json({ id: 'run-5', asOf: '2026-09-30', recordedAt: '2026-09-30T06:00:00Z', provenance: 'synthetic', status: 'TIED_OUT', positionCount: 3, mismatchCount: 0, inputHash: 'abcdef0123456789', mismatches: [] })
    }
    await renderPage(<SnapshotDetailPage params={Promise.resolve({ id: 'run-5' })} />)
    const heading = await screen.findByText(/^(Risk limits|Rizikové limity)$/)
    const card = heading.closest('.card') as HTMLElement
    await vi.waitFor(() => expect(card.textContent!.length).toBeGreaterThan(heading.textContent!.length))
    expect(card.querySelector('table')).toBeNull()
    expect(card.querySelector('.badge-success')).toBeNull()
    expect(screen.queryByText(/Within limit|V limitu/)).toBeNull()
  })

  it('shows a loan instrument by its contract number, and an instrument without one by its id', async () => {
    const instrument = (id: string, contractNumber?: string | null) => ({
      id, kind: 'AMORTISING_LOAN', glAccountCode: '1200', currency: 'CZK', outstanding: 1000, valueDate: '2026-01-10',
      maturityDate: '2027-01-15', rateTerms: null, counterpartyRef: null, ifrs9Stage: null, loan: null,
      ...(contractNumber === undefined ? {} : { contractNumber }),
    })
    router = url => {
      if (url.includes('/instruments')) {
        return json({
          runId: 'run-4', asOf: '2026-09-30',
          instruments: [instrument('loan-uuid-1', 'UV-2026-000123'), instrument('loan-uuid-2', null), instrument('loan-uuid-3')],
        })
      }
      if (url.includes('/curve-sets')) return json({ curveSets: [] })
      return json({ id: 'run-4', asOf: '2026-09-30', recordedAt: '2026-09-30T06:00:00Z', provenance: 'production', status: 'TIED_OUT', positionCount: 3, mismatchCount: 0, inputHash: 'abcdef0123456789', mismatches: [] })
    }
    await renderPage(<SnapshotDetailPage params={Promise.resolve({ id: 'run-4' })} />)
    // #11107: the loan with a number is referenced by it; the others fall back to the id handle.
    const numbered = await screen.findByRole('button', { name: /UV-2026-000123/ })
    expect(numbered.getAttribute('title')).toMatch(/The reference is the contract number/)
    const fallbacks = screen.getAllByRole('button', { name: /Loan LOAN/ })
    expect(fallbacks).toHaveLength(2)
    expect(fallbacks[0].getAttribute('title')).toMatch(/No contract number/)
  })
})

const IRRBB = (ratio: number | null, tier1: boolean) => ({
  runId: 'run-3', asOf: '2026-09-30', provenance: 'synthetic', curveSetId: 'cs-1', curveSetProvenance: 'synthetic', curveSetSource: 'desk',
  gaps: [{ currency: 'EUR', buckets: [{ bucket: 'overnight', assets: 0, liabilities: 60, gap: -60, cumulativeGap: -60 }, { bucket: '1-2Y', assets: 1000, liabilities: 0, gap: 1000, cumulativeGap: 940 }], totalAssets: 1000, totalLiabilities: 60, totalGap: 940 }],
  scenarios: [
    { scenario: 'parallel-up', currencies: [{ currency: 'EUR', basePv: 950, shockedPv: 930, deltaEve: -20, eveLoss: 20, deltaNii: 3.5 }], aggregateLoss: 20 },
    { scenario: 'steepener', currencies: [{ currency: 'EUR', basePv: 950, shockedPv: 945, deltaEve: -5, eveLoss: 5, deltaNii: null }], aggregateLoss: 5 },
  ],
  worstCase: { scenario: 'parallel-up', loss: 20, currency: 'EUR', byCurrency: { EUR: 'parallel-up' } },
  outlierTest: { tier1Supplied: tier1, tier1Capital: tier1 ? 100 : null, currency: 'EUR', threshold: 0.15, ratio, breached: ratio === null ? null : ratio > 0.15, note: tier1 ? 'ratio note' : 'Tier 1 not supplied' },
  shockNotConfigured: ['CZK'], unpriced: [],
  assumptions: {
    model: { id: 'nmd-linear-core', version: '1.0.0', coreRatio: 0.7, coreRunoffYears: 5, annualDepositRate: 0 },
    shockSizes: [{ currency: 'EUR', parallelBp: 200, shortBp: 250, longBp: 100 }], shockSource: 'BCBS d368 Annex 2', shortDecayYears: 4,
    postShockFloor: null, postShockFloorSource: 'No post-shock floor configured', nmdRepricing: 'repricing = run-off', floatingRepricing: 'next reset',
    eveBasis: 'run-off', niiBasis: 'constant balance sheet', niiHorizonMonths: 12, currencyAggregation: 'd368',
  },
})

describe('IRRBB', () => {
  const route = (url: string) => {
    if (url.includes('/curve-sets')) return json({ curveSets: [{ id: 'cs-1', asOf: '2026-09-30', provenance: 'synthetic', source: 'desk', recordedAt: '2026-09-30T06:00:00Z', indices: ['ESTR'] }] })
    if (url.includes('tier1Capital=100')) return json(IRRBB(0.2, true))
    return json(IRRBB(null, false))
  }

  it('without Tier 1 shows no ratio, never sends one, and states not-configured currencies', async () => {
    router = route
    await renderPage(<SnapshotIrrbbPage params={Promise.resolve({ id: 'run-3' })} />)
    await screen.findByText(/Tier 1 not supplied — the ratio|Tier 1 nezadán/)
    const irrbbCalls = calls.filter(c => c.url.includes('/irrbb'))
    expect(irrbbCalls.length).toBeGreaterThan(0)
    expect(irrbbCalls.every(c => c.url.startsWith('/api/svc/risk-engine/api/v1/risk/snapshots/run-3/irrbb?') && !c.url.includes('tier1Capital'))).toBe(true)
    expect(screen.queryByText(/Breached|Překročeno/)).toBeNull()
    expect(screen.getByText(/No shock sizes are configured|nejsou nastaveny/).textContent).toContain('CZK')
    expect(document.querySelectorAll('tr[data-worst="true"]').length).toBe(1)
    expect(screen.getByText(/BCBS d368 Annex 2/)).toBeTruthy()
  })

  it('shows the outlier ratio only after the user supplies Tier 1', async () => {
    router = route
    await renderPage(<SnapshotIrrbbPage params={Promise.resolve({ id: 'run-3' })} />)
    await screen.findByText(/Tier 1 not supplied — the ratio|Tier 1 nezadán/)
    fireEvent.change(screen.getByLabelText(/^(Tier 1 capital|Kapitál Tier 1)$/), { target: { value: '100' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Apply|Použít/ })) })
    await screen.findByText(/Breached|Překročeno/)
    expect(calls.some(c => c.url.includes('tier1Capital=100'))).toBe(true)
  })

  it('an invalid Tier 1 cannot be applied', async () => {
    router = route
    await renderPage(<SnapshotIrrbbPage params={Promise.resolve({ id: 'run-3' })} />)
    await screen.findByText(/Tier 1 not supplied — the ratio|Tier 1 nezadán/)
    fireEvent.change(screen.getByLabelText(/^(Tier 1 capital|Kapitál Tier 1)$/), { target: { value: '-5' } })
    expect((screen.getByRole('button', { name: /Apply|Použít/ }) as HTMLButtonElement).disabled).toBe(true)
  })
})

const line = (label: string, amount: number, factor: number | null, citation: string, factorKey: string | null = 'k') =>
  ({ label, glAccountCode: null, amount, factor, factorKey, weighted: factor === null ? 0 : amount * factor, citation })
const CZK = (inflowBinding: boolean, lcrRatio: number | null) => ({
  currency: 'CZK',
  lcr: {
    hqla: { lines: [{ level: 'L1', glClass: 'hqla-l1-cash-or-reserves', glAccountCode: '9001', marketValue: 100, haircut: 0, afterHaircut: 100 }],
      level1: 100, level2a: 0, level2b: 0, adjustmentFor15Cap: 0, adjustmentFor40Cap: 0, level2bCapBinding: false, level2CapBinding: false, stock: 100 },
    outflows: [line('Retail deposits, less stable (2 customer accounts)', 1500, 0.1, 'BCBS d238 ¶79')],
    inflows: [line('GL 1001 (deposit-at-fi-operational)', 1500, 0, 'BCBS d238 ¶156')],
    totalOutflows: 150, totalInflows: inflowBinding ? 200 : 0, inflowCap: 112.5, cappedInflows: inflowBinding ? 112.5 : 0,
    inflowCapBinding: inflowBinding, netOutflows: inflowBinding ? 37.5 : 150, ratio: lcrRatio,
  },
  nsfr: {
    asf: [line('Retail deposits, less stable', 1500, 0.9, 'BCBS d295 ¶23'), line('GL 6040 (capital-deduction)', -10, null, 'BCBS d295 ¶17', null)],
    rsf: [line('GL 1001', 1500, 0.5, 'BCBS d295 ¶40(d)')], totalAsf: 1350, totalRsf: 750, ratio: 1.8,
  },
})
const LIQ = (opts: { unclassified?: boolean; inflowBinding?: boolean; lcrRatio?: number | null } = {}) => {
  const c = CZK(opts.inflowBinding ?? false, opts.lcrRatio === undefined ? 0.666667 : opts.lcrRatio)
  return {
    runId: 'run-4', asOf: '2026-09-30', provenance: 'synthetic', parameterSetId: 'bcbs-d238-d295', parameterSetVersion: '1',
    currencies: [c], total: c,
    unclassified: opts.unclassified ? [{ glAccountCode: '1000', glAccountType: 'ASSET', currency: 'CZK', amount: 300, reason: 'not mapped' }] : [],
    notes: [],
    assumptions: {
      parameterSetId: 'bcbs-d238-d295', parameterSetVersion: '1', source: 'BCBS d238 (Jan 2013) and d295 (Oct 2014)',
      scope: 'BCBS standard factors; EU CRR / Delegated Regulation (EU) 2015/61 deviations not applied.',
      factors: [{ key: 'lcr-inflow-cap', value: 0.75, citation: 'BCBS d238 ¶69, ¶144' }],
      classification: {
        retailStableShare: 0, operationalDepositShare: 0, tier2OverOneYearShare: 0, loansQualifyForLowRiskWeight: false,
        glAccounts: [{ key: '1001', glClass: 'deposit-at-fi-operational', description: 'Balance at another bank' }], glAccountTypes: [],
        choices: ['All retail deposits are less stable (d238 ¶80).'],
      },
      hqlaCapMethod: 'Annex 1', loanInflows: '¶153', loanRsf: '¶29', notInData: 'none', currencyAggregation: 'per currency',
    },
  }
}

describe('Liquidity (LCR / NSFR)', () => {
  it('shows both ratios, the component tables, the parameter set, citations and the scope statement', async () => {
    router = () => json(LIQ())
    await renderPage(<SnapshotLiquidityPage params={Promise.resolve({ id: 'run-4' })} />)
    await screen.findByTestId('lcr-CZK')
    expect(calls.every(c => c.url === '/api/svc/risk-engine/api/v1/risk/snapshots/run-4/liquidity')).toBe(true)
    expect(screen.getByTestId('nsfr-CZK').textContent).toContain('180')
    expect(screen.getByText(/bcbs-d238-d295 v1/)).toBeTruthy()
    expect(screen.getAllByText(/2015\/61 deviations not applied/).length).toBeGreaterThan(0)
    expect(document.querySelector('td[title="BCBS d238 ¶69, ¶144"]')).not.toBeNull()
    expect(screen.getAllByText(/Synthetic data|Syntetická data/).length).toBeGreaterThan(0)
    expect(screen.queryByText(/Unclassified balances|Nezařazené zůstatky/)).toBeNull()
  })

  it('warns about unclassified GL balances with their amounts', async () => {
    router = () => json(LIQ({ unclassified: true }))
    await renderPage(<SnapshotLiquidityPage params={Promise.resolve({ id: 'run-4' })} />)
    await screen.findByText(/Unclassified balances|Nezařazené zůstatky/)
    expect(document.querySelectorAll('tr[data-unclassified="true"]').length).toBe(1)
    expect(screen.getByRole('alert').textContent).toContain('1000')
  })

  it('names a binding inflow cap and shows an undefined ratio as undefined, never 0 %', async () => {
    router = () => json(LIQ({ inflowBinding: true, lcrRatio: null }))
    await renderPage(<SnapshotLiquidityPage params={Promise.resolve({ id: 'run-4' })} />)
    await screen.findByTestId('lcr-CZK')
    expect(screen.getByTestId('lcr-CZK').textContent).toMatch(/undefined|nedefinováno/)
    expect(screen.getByText(/inflow cap \(75%\)|strop přítoků/).textContent).toContain('87')
  })

  it('an UNTIED run (409) is shown as unavailable, not as figures', async () => {
    router = () => json({ error: 'UNTIED', runId: 'run-4', mismatches: [] }, 409)
    await renderPage(<SnapshotLiquidityPage params={Promise.resolve({ id: 'run-4' })} />)
    await act(async () => { await Promise.resolve() })
    expect(screen.queryByTestId('lcr-CZK')).toBeNull()
  })
})

const CAP_CZK = {
  currency: 'CZK',
  classes: [
    { exposureClass: 'retail', ead: 10000, rwa: 10000, citations: ['BCBS d424 ¶57 (other retail: 100%)'] },
    { exposureClass: 'bank', ead: 6500, rwa: 9750, citations: ['BCBS d424 ¶21 Table 7, ¶26, ¶28-29 (SCRA Grade C base: 150%)'] },
    { exposureClass: 'sovereign-and-central-bank', ead: 20000, rwa: 0, citations: ['BCBS d424 ¶8 (national discretion)'] },
  ],
  lines: [{ exposureClass: 'bank', label: 'GL 1500 (bank, GL level)', glAccountCode: '1500', instrumentId: null, ead: 5000,
    riskWeight: 1.5, rwa: 7500, factorKey: 'rw-bank-scra-grade-c', citation: 'BCBS d424 ¶21 Table 7, ¶26, ¶28-29 (SCRA Grade C base: 150%)' }],
  totalEad: 36500, totalRwa: 19750,
  ownFunds: { lines: [], cet1BeforeDeductions: 3500, cet1Deductions: -200, cet1: 3300, at1: 300, tier1: 3600, tier2: 400, total: 4000 },
}
const CAP = (opts: { unclassified?: boolean; noCapital?: boolean } = {}) => {
  const c = opts.noCapital ? { ...CAP_CZK, ownFunds: null } : CAP_CZK
  const ratio = (r: number, m: number) => ({ ratio: r, minimum: m, meetsMinimum: r >= m, citation: 'BCBS bcbs189 ¶50' })
  return {
    runId: 'run-5', asOf: '2026-09-30', provenance: 'synthetic', parameterSetId: 'bcbs-d424-sa', parameterSetVersion: '1',
    currencies: [c], total: c, ownFundsRequirement: 1580,
    ratios: opts.noCapital ? null : { cet1: ratio(0.167089, 0.045), tier1: ratio(0.182278, 0.06), total: ratio(0.202532, 0.08) },
    ratiosNotComputable: opts.noCapital ? 'no own-funds GL account (openbank.risk.capital.sa.classification own-funds-*) is in the snapshot' : null,
    unclassified: opts.unclassified ? [{ glAccountCode: '1000', glAccountType: 'ASSET', currency: 'CZK', amount: 300, reason: 'GL account not mapped' }] : [],
    notes: ['credit-risk only: UPPER BOUND'],
    assumptions: {
      parameterSetId: 'bcbs-d424-sa', parameterSetVersion: '1', source: 'BCBS d424 (Dec 2017) Part I',
      scope: 'BCBS d424 SA weights; EU CRR Part Three Title II Chapter 2 not applied. Pillar 1 credit risk only.',
      factors: [{ key: 'rw-bank-scra-grade-c', value: 1.5, citation: 'BCBS d424 ¶21 Table 7 (Grade C)' }],
      classification: {
        retailTreatment: 'other-retail', bankScraGrade: 'C', domesticCurrency: 'CZK',
        glAccounts: [{ key: '1500', glClass: 'bank', description: 'Claim on a bank, unrated' }], glAccountTypes: [],
        choices: ['Every bank exposure is unrated and weighted at SCRA Grade C.'],
      },
      exposureValue: 'carrying amount', creditRiskMitigation: 'None applied.', offBalanceSheet: 'None in the snapshot',
      defaulted: 'IFRS 9 stage 3', ownFunds: '6000-6060', creditRiskOnly: 'credit-risk only: UPPER BOUND', currencyAggregation: 'per currency',
    },
  }
}

describe('Capital (Pillar 1 credit risk, standardised approach)', () => {
  it('shows RWA by class with citations, the 8% requirement, ratios, the parameter set and the scope', async () => {
    router = () => json(CAP())
    await renderPage(<SnapshotCapitalPage params={Promise.resolve({ id: 'run-5' })} />)
    await screen.findByTestId('total-rwa')
    expect(calls.every(c => c.url === '/api/svc/risk-engine/api/v1/risk/snapshots/run-5/capital')).toBe(true)
    expect(screen.getByTestId('total-rwa').textContent).toMatch(/19[\s\u00a0,.]?750/)
    expect(screen.getByTestId('requirement').textContent).toMatch(/1[\s\u00a0,.]?580/)
    expect(screen.getByTestId('ratio-total').textContent).toContain('20')
    expect(document.querySelectorAll('tr[data-class]').length).toBe(3)
    // Citations render as a plain article reference; the engine's full citation is the cell's title.
    expect(document.querySelector('tr[data-class="bank"] td[title*="SCRA Grade C base: 150%"]')).not.toBeNull()
    expect(screen.getByText(/bcbs-d424-sa v1/)).toBeTruthy()
    expect(screen.getAllByText(/EU CRR Part Three Title II Chapter 2 not applied/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/UPPER BOUND/).length).toBe(1)
    expect(screen.getAllByText(/Synthetic data|Syntetická data/).length).toBeGreaterThan(0)
    expect(screen.queryByText(/Unclassified balances|Nezařazené zůstatky/)).toBeNull()
  })

  it('says why ratios are not computable instead of showing any', async () => {
    router = () => json(CAP({ noCapital: true }))
    await renderPage(<SnapshotCapitalPage params={Promise.resolve({ id: 'run-5' })} />)
    await screen.findByTestId('ratios-not-computable')
    expect(screen.getByTestId('ratios-not-computable').textContent).toContain('no own-funds GL account')
    expect(screen.queryByTestId('ratio-total')).toBeNull()
  })

  it('warns about unclassified balances with their amounts and reason', async () => {
    router = () => json(CAP({ unclassified: true }))
    await renderPage(<SnapshotCapitalPage params={Promise.resolve({ id: 'run-5' })} />)
    await screen.findByText(/Unclassified balances|Nezařazené zůstatky/)
    expect(document.querySelectorAll('tr[data-unclassified="true"]').length).toBe(1)
    expect(screen.getByRole('alert').textContent).toContain('1000')
    expect(screen.getByRole('alert').textContent).toContain('GL account not mapped')
  })

  it('an UNTIED run (409) is shown as unavailable, not as figures', async () => {
    router = () => json({ error: 'UNTIED', runId: 'run-5', mismatches: [] }, 409)
    await renderPage(<SnapshotCapitalPage params={Promise.resolve({ id: 'run-5' })} />)
    await act(async () => { await Promise.resolve() })
    expect(screen.queryByTestId('total-rwa')).toBeNull()
  })
})

const fRow = (fromDay: number, toDay: number, behaviouralOutflows: number, cumulative: number, minCumulative = cumulative) => ({
  fromDay, toDay, from: '2026-10-01', to: `2026-10-${String(toDay).padStart(2, '0')}`,
  contractualInflows: 0, contractualOutflows: 0, behaviouralInflows: 0, behaviouralOutflows,
  inflows: 0, outflows: behaviouralOutflows, net: behaviouralOutflows, cumulative, minCumulative,
})
const FORECAST = (survival: number | null, hqla: boolean) => ({
  runId: 'run-6', asOf: '2026-09-30', provenance: 'synthetic', curveSetId: 'cs-1', curveSetProvenance: 'synthetic',
  model: { id: 'nmd-linear-core', version: '1.0.0', coreRatio: 0.7, coreRunoffYears: 5, annualDepositRate: 0 },
  parameterSetId: 'bcbs-d238-d295', parameterSetVersion: '2', horizonDays: 90, dailyDays: 30,
  currencies: [{
    currency: 'CZK',
    hqla: hqla ? { lines: [], level1: 1000, level2a: 0, level2b: 0, adjustmentFor15Cap: 0, adjustmentFor40Cap: 0, level2bCapBinding: false, level2CapBinding: false, stock: 1000 } : null,
    openingLiquidity: hqla ? 1000 : 0,
    survivalHorizonDays: survival, survivalDate: survival === null ? null : '2026-10-01',
    minimumCumulative: survival === null ? 550 : -450, flowsBeyondHorizon: 57,
    ladder: survival === null ? [fRow(1, 1, -450, 550), fRow(2, 2, 0, 550)] : [fRow(1, 1, -450, -450), fRow(2, 2, 0, -450)],
  }],
  assumptions: [
    { key: 'opening-liquidity', statement: 'Opening liquidity is the HQLA stock as the LCR reports it.' },
    { key: 'new-business-not-modelled', statement: 'New business is not modelled.' },
  ],
})

describe('Liquidity forecast (survival horizon)', () => {
  const route = (body: unknown, status = 200) => (url: string) => url.includes('/curve-sets')
    ? json({ curveSets: [{ id: 'cs-1', asOf: '2026-09-30', provenance: 'synthetic', source: 'desk', recordedAt: '2026-09-30T06:00:00Z', indices: ['CZEONIA'] }] })
    : json(body, status)

  it('shows the ladder, the opening HQLA and a survival horizon that is not breached as such, never as a day', async () => {
    router = route(FORECAST(null, true))
    await renderPage(<SnapshotLiquidityForecastPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByTestId('survival-CZK')
    const forecastCalls = calls.filter(c => c.url.includes('/liquidity-forecast'))
    expect(forecastCalls.length).toBeGreaterThan(0)
    expect(forecastCalls.every(c => c.url.startsWith('/api/svc/risk-engine/api/v1/risk/snapshots/run-6/liquidity-forecast?') && c.url.includes('curveSetId=cs-1') && c.url.includes('horizonDays=90'))).toBe(true)
    expect(screen.getByTestId('survival-CZK').textContent).toMatch(/no shortfall within 90 days|bez výpadku do 90 dnů/)
    expect(document.querySelectorAll('tr[data-negative="true"]').length).toBe(0)
    expect(screen.getByText(/New business is not modelled/)).toBeTruthy()
    expect(screen.getByText(/bcbs-d238-d295 v2/)).toBeTruthy()
    expect(screen.getAllByText(/Synthetic data|Syntetická data/).length).toBeGreaterThan(0)
  })

  it('names the breach day, marks negative rows and says when a currency holds no HQLA', async () => {
    router = route(FORECAST(1, false))
    await renderPage(<SnapshotLiquidityForecastPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByTestId('survival-CZK')
    expect(screen.getByTestId('survival-CZK').textContent).toMatch(/day 1|1\. den/)
    expect(document.querySelectorAll('tr[data-negative="true"]').length).toBe(2)
    expect(screen.getByText(/no HQLA held in this currency|nemá žádná HQLA/)).toBeTruthy()
  })

  it('marks a weekly row that dips negative mid-week even though it ends positive', async () => {
    const body = FORECAST(null, true)
    body.currencies[0].ladder = [fRow(1, 1, -450, 550), fRow(31, 37, 0, 120, -80)]
    router = route(body)
    await renderPage(<SnapshotLiquidityForecastPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByTestId('survival-CZK')
    expect(screen.getByTestId('min-cumulative-CZK-31').textContent).toMatch(/80/)
    const negative = document.querySelectorAll('tr[data-negative="true"]')
    expect(negative.length).toBe(1)
    expect(negative[0].textContent).toMatch(/31–37/)
  })

  it('sends the chosen horizon', async () => {
    router = route(FORECAST(null, true))
    await renderPage(<SnapshotLiquidityForecastPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByTestId('survival-CZK')
    await act(async () => { fireEvent.change(screen.getByLabelText(/^(Forecast horizon|Horizont prognózy)$/), { target: { value: '365' } }) })
    expect(calls.some(c => c.url.includes('/liquidity-forecast') && c.url.includes('horizonDays=365'))).toBe(true)
  })

  it('an UNTIED run (409) is shown as unavailable, not as figures', async () => {
    router = route({ error: 'UNTIED', runId: 'run-6', mismatches: [] }, 409)
    await renderPage(<SnapshotLiquidityForecastPage params={Promise.resolve({ id: 'run-6' })} />)
    await act(async () => { await Promise.resolve() })
    expect(screen.queryByTestId('survival-CZK')).toBeNull()
  })
})

const reserveLine = (label: string, amount: number, reserveClass: string, glAccountCode: string | null = '9001') =>
  ({ label, glAccountCode, amount, reserveClass })
const MIN_RESERVES = (opts: {
  holdingsNotStated?: string | null
  unclassified?: boolean
  excluded?: boolean
  totalHoldings?: number | null
  requirement?: number | null
  surplus?: number | null
  requirementNotStated?: string
} = {}) => ({
  runId: 'run-6', asOf: '2026-09-30', provenance: 'synthetic', parameterSetId: 'cnb-min-reserves', parameterSetVersion: '1',
  currencies: [{
    currency: 'CZK',
    lines: [reserveLine('Client deposits (retail)', 100000, 'reserve-base', '2200')],
    base: opts.requirementNotStated ? null : 100000, rate: 0.02,
    requirement: opts.requirementNotStated ? null : 2000, requirementNotStated: opts.requirementNotStated ?? null,
  }],
  holdingCurrency: 'CZK',
  holdings: opts.holdingsNotStated ? null : [reserveLine('ČNB current account', opts.totalHoldings ?? 2500, 'cnb-account')],
  totalHoldings: opts.holdingsNotStated ? null : (opts.totalHoldings ?? 2500),
  holdingsNotStated: opts.holdingsNotStated ?? null,
  requirement: opts.holdingsNotStated ? null : (opts.requirement ?? 2000),
  surplus: opts.holdingsNotStated ? null : (opts.surplus ?? 500),
  remunerationRate: 0, remuneration: 0,
  excluded: opts.excluded ? [{ glAccountCode: '2500', amount: 5000, reserveClass: 'liability-to-bank' }] : [],
  unclassified: opts.unclassified ? [{ glAccountCode: '1000', glAccountType: 'ASSET', currency: 'CZK', amount: 300, reason: 'not mapped' }] : [],
  notes: [],
  assumptions: {
    parameterSetId: 'cnb-min-reserves', parameterSetVersion: '1', source: 'ČNB Opatření o povinných minimálních rezervách',
    rate: 0.02, remunerationRate: 0, holdingCurrency: 'CZK',
    glAccounts: [{ key: '2200', reserveClass: 'reserve-base', description: 'Client deposits' }],
    glAccountTypes: [],
  },
})

describe('ČNB minimum reserve requirement', () => {
  it('renders the base, requirement, holdings and surplus figures with the parameter set', async () => {
    router = () => json(MIN_RESERVES())
    await renderPage(<SnapshotMinReservesPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByTestId('total-holdings')
    expect(screen.getByText(/As of 2026-09-30|Stav k 2026-09-30/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /Copy run ID|Kopírovat ID běhu/ }).closest('span')?.textContent).toContain('run-6')
    expect(calls[0].url).toBe('/api/svc/risk-engine/api/v1/risk/snapshots/run-6/min-reserves')
    // every other call is the maintenance-period read, through the same BFF path
    expect(calls.slice(1).every(c => c.url.startsWith('/api/svc/risk-engine/api/v1/risk/min-reserves/periods'))).toBe(true)
    expect(screen.getByTestId('total-holdings').textContent).toMatch(/2[\s ,.]?500/)
    expect(screen.getByTestId('requirement').textContent).toMatch(/2[\s ,.]?000/)
    expect(screen.getByTestId('surplus').textContent).toMatch(/500/)
    expect(screen.getByTestId('requirement-CZK').textContent).toMatch(/2[\s ,.]?000/)
    expect(screen.getByText(/cnb-min-reserves v1/)).toBeTruthy()
    expect(screen.getAllByText(/Synthetic data|Syntetická data/).length).toBeGreaterThan(0)
    expect(screen.queryByTestId('holdings-not-stated')).toBeNull()
    expect(screen.queryByText(/Unclassified balances|Nezařazené zůstatky/)).toBeNull()
  })

  it('shows the holdingsNotStated reason prominently and renders no zero when holdings are null', async () => {
    router = () => json(MIN_RESERVES({ holdingsNotStated: 'No GL account is mapped as the ČNB current account' }))
    await renderPage(<SnapshotMinReservesPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByTestId('holdings-not-stated')
    expect(screen.getByTestId('holdings-not-stated').textContent).toContain('No GL account is mapped as the ČNB current account')
    expect(screen.queryByTestId('total-holdings')).toBeNull()
    expect(screen.queryByTestId('surplus')).toBeNull()
    // No stand-in zero anywhere on the page for the not-stated figures.
    expect(document.body.textContent).not.toMatch(/\b0[.,]00\b.*(?:ČNB|holdings)/)
  })

  it('does not invent a holdings total when individual holdings are present', async () => {
    router = () => json({ ...MIN_RESERVES(), totalHoldings: null })
    await renderPage(<SnapshotMinReservesPage params={Promise.resolve({ id: 'run-6' })} />)
    const heading = await screen.findByRole('heading', { name: /ČNB current-account holdings|Zůstatek na účtu u ČNB/, level: 3 })
    const totalCell = heading.parentElement?.querySelector('tbody tr:last-child td:last-child')
    expect(totalCell?.textContent).toMatch(/not stated|neuvedeno/)
    expect(screen.getByTestId('total-holdings').textContent).toMatch(/not stated|neuvedeno/)
  })

  it('a currency whose base is not stated shows the reason and no base or requirement figure', async () => {
    router = () => json({
      ...MIN_RESERVES({ requirementNotStated: 'A LIABILITY balance in this currency is not classified' }),
      requirement: null, surplus: null,
    })
    await renderPage(<SnapshotMinReservesPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByTestId('requirement-not-stated-CZK')
    expect(screen.getByTestId('requirement-not-stated-CZK').textContent).toContain('is not classified')
    expect(screen.queryByTestId('requirement-CZK')).toBeNull()
    expect(screen.getByTestId('requirement').textContent).toMatch(/not stated|neuvedeno/)
  })

  it('lists excluded and unclassified balances separately, with their amounts and reason', async () => {
    router = () => json(MIN_RESERVES({ excluded: true, unclassified: true }))
    await renderPage(<SnapshotMinReservesPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByText(/Unclassified balances|Nezařazené zůstatky/)
    expect(document.querySelectorAll('tr[data-excluded="true"]').length).toBe(1)
    expect(document.querySelectorAll('tr[data-unclassified="true"]').length).toBe(1)
    expect(screen.getByRole('alert').textContent).toContain('1000')
    expect(screen.getByText(/Excluded balances|Vyloučené zůstatky/)).toBeTruthy()
  })

  const PERIOD_ID = '2026-09'
  const CALENDAR = {
    calendarId: 'cnb-pmr-maintenance-calendar', calendarVersion: '1', calendarStatus: 'sample-unverified',
    calendarSource: 'SAMPLE / UNVERIFIED', notes: ['SAMPLE / UNVERIFIED maintenance-period calendar'],
    periods: [{ id: PERIOD_ID, start: '2026-09-01', end: '2026-09-30', baseReferenceDate: '2026-08-31' }],
  }
  const PERIOD = (over: Record<string, unknown> = {}) => ({
    calendarId: CALENDAR.calendarId, calendarVersion: '1', calendarStatus: 'sample-unverified', calendarSource: 'SAMPLE / UNVERIFIED',
    period: CALENDAR.periods[0], evaluationDate: '2026-09-30', parameterSetId: 'cnb-pmr', parameterSetVersion: '2', holdingCurrency: 'CZK',
    baseRunId: 'run-base', requirement: 2000, requirementNotStated: null,
    daysInPeriod: 30, daysElapsed: 3, daysRemaining: 27, daysWithData: 3, coverage: 1, missingDays: [],
    days: [{ date: '2026-09-01', runId: 'r1', holdings: 1900 }, { date: '2026-09-02', runId: 'r2', holdings: 2000 }, { date: '2026-09-03', runId: 'r3', holdings: 2100 }],
    averageHoldings: 2000, averageNotStated: null, remainingRequiredAverage: 2000, dailyHoldingProposal: 2000,
    proposal: [{ date: '2026-09-04', amount: 2000 }], proposalNotStated: null, requirementMet: null,
    notes: ['SAMPLE / UNVERIFIED maintenance-period calendar'], ...over,
  })
  const periodRouter = (period: unknown, calendar: unknown = CALENDAR) => (url: string) => {
    if (url.includes(`/min-reserves/periods/${PERIOD_ID}`)) return json(period)
    if (url.includes('/min-reserves/periods')) return json(calendar)
    return json(MIN_RESERVES())
  }

  it('shows the maintenance period of the run date: sample badge, requirement, average, coverage and proposal', async () => {
    router = periodRouter(PERIOD())
    await renderPage(<SnapshotMinReservesPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByTestId('period-proposal')
    expect(calls.some(c => c.url === '/api/svc/risk-engine/api/v1/risk/min-reserves/periods?asOf=2026-09-30')).toBe(true)
    expect(calls.some(c => c.url === `/api/svc/risk-engine/api/v1/risk/min-reserves/periods/${PERIOD_ID}?asOf=2026-09-30`)).toBe(true)
    expect(screen.getByText(/Sample, unverified calendar|Vzorový, neověřený kalendář/)).toBeTruthy()
    expect(screen.getByTestId('period-requirement').textContent).toMatch(/2[\s ,.]?000/)
    expect(screen.getByTestId('period-average').textContent).toMatch(/2[\s ,.]?000/)
    expect(screen.getByTestId('period-coverage').textContent).toMatch(/3 \/ 3/)
    expect(document.querySelectorAll('tr[data-period-day="true"]').length).toBe(3)
  })

  it('a not-stated average and proposal show their reasons, never a zero figure', async () => {
    router = periodRouter(PERIOD({
      averageHoldings: null, averageNotStated: 'No ledger GL account is mapped as the bank current account at the ČNB',
      remainingRequiredAverage: null, dailyHoldingProposal: null, proposal: null, proposalNotStated: 'holdings not stated',
      days: [{ date: '2026-09-01', runId: 'r1', holdings: null }], daysWithData: 1, coverage: 0.3333, missingDays: ['2026-09-02', '2026-09-03'],
    }))
    await renderPage(<SnapshotMinReservesPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByTestId('period-average-not-stated')
    expect(screen.getByTestId('period-average-not-stated').textContent).toContain('current account at the ČNB')
    expect(screen.getByTestId('period-proposal-not-stated').textContent).toContain('holdings not stated')
    expect(screen.queryByTestId('period-average')).toBeNull()
    expect(screen.queryByTestId('period-proposal')).toBeNull()
    expect(screen.getByTestId('period-missing-days').textContent).toContain('2026-09-02')
    expect(screen.getByTestId('maintenance-period').textContent).not.toMatch(/\b0[.,]00\b/)
  })

  it('a run date outside the calendar says so instead of showing period figures', async () => {
    router = periodRouter(PERIOD(), { ...CALENDAR, periods: [] })
    await renderPage(<SnapshotMinReservesPage params={Promise.resolve({ id: 'run-6' })} />)
    await screen.findByTestId('period-none')
    expect(calls.some(c => c.url.includes(`/min-reserves/periods/${PERIOD_ID}`))).toBe(false)
  })

  it('an UNTIED run (409) is shown as unavailable, not as figures', async () => {
    router = () => json({ error: 'UNTIED', runId: 'run-6', mismatches: [] }, 409)
    await renderPage(<SnapshotMinReservesPage params={Promise.resolve({ id: 'run-6' })} />)
    await act(async () => { await Promise.resolve() })
    expect(screen.queryByTestId('total-holdings')).toBeNull()
  })
})

// Owner feedback: the IRRBB and liquidity-forecast pages showed a raw run UUID, an empty unlabeled
// curve-set select and a free-text Tier 1 field with no explanation.
describe('IRRBB and liquidity forecast — run context, curve-set picker and Tier 1', () => {
  const RUN_ID = '01a0f402-890a-71ed-9f55-d54f11f19f89'
  const RUN = {
    id: RUN_ID, asOf: '2026-09-30', recordedAt: '2026-09-30T20:30:01Z', provenance: 'synthetic', status: 'TIED_OUT',
    positionCount: 129, mismatchCount: 0, inputHash: 'abc', mismatches: [], requestedBy: 'system:risk-engine-eod-snapshot',
  }
  const SET = {
    id: 'cs-ref', asOf: '2026-09-30', provenance: 'synthetic', recordedAt: '2026-10-01T06:00:00Z',
    source: 'OpenBank sandbox reference curves v1', indices: ['CZEONIA', 'ESTR', 'EURIBOR_3M', 'PRIBOR_3M'],
  }
  const routeWith = (opts: { sets: unknown[]; capital?: unknown; irrbb?: (url: string) => unknown; forecast?: unknown }) => (url: string) => {
    if (url.includes('/curve-sets')) return json({ curveSets: opts.sets })
    if (url.includes('/capital')) return opts.capital ? json(opts.capital) : json({ error: 'not here' }, 404)
    if (url.includes('/irrbb')) return json(opts.irrbb ? opts.irrbb(url) : IRRBB(null, false))
    if (url.includes('/liquidity-forecast')) return json(opts.forecast ?? FORECAST(null, true))
    if (url.endsWith(`/snapshots/${RUN_ID}`)) return json(RUN)
    return json({}, 404)
  }

  it('describes the run in words, keeps the id small, and asks only for curve sets of the run date', async () => {
    router = routeWith({ sets: [SET] })
    await renderPage(<SnapshotIrrbbPage params={Promise.resolve({ id: RUN_ID })} />)
    await screen.findByText(/Tier 1 not supplied — the ratio|Tier 1 nezadán/)
    const subtitle = screen.getByTestId('run-subtitle').textContent ?? ''
    expect(subtitle).toMatch(/30\. 9\. 2026|30\/09\/2026/)
    expect(subtitle).toMatch(/129 (pozic|positions)/)
    expect(subtitle).toMatch(/plánovaný denní běh|scheduled end-of-day run/)
    expect(subtitle).not.toContain(RUN_ID)
    expect(calls.some(c => c.url.includes('/curve-sets?') && c.url.includes('asOf=2026-09-30'))).toBe(true)
    const option = screen.getByRole('option') as HTMLOptionElement
    expect(option.textContent).toMatch(/^CZK \+ EUR · (k|as of) .+ · OpenBank sandbox reference curves v1 · (ukázková data|demo data)$/)
    expect(option.textContent).not.toContain('cs-ref')
  })

  it('with no curve set for the run date, disables the select, links to the curve-set section and computes nothing', async () => {
    router = routeWith({ sets: [] })
    await renderPage(<SnapshotIrrbbPage params={Promise.resolve({ id: RUN_ID })} />)
    const cta = await screen.findByTestId('curve-set-cta')
    expect(cta.querySelector('a')?.getAttribute('href')).toBe('/balance-sheet/curve-sets')
    expect((screen.getByLabelText(/^(Yield-curve set|Sada výnosových křivek)$/) as HTMLSelectElement).disabled).toBe(true)
    expect(calls.some(c => c.url.includes('/irrbb'))).toBe(false)
  })

  it('prefills Tier 1 from the run’s own-funds read, says where it came from, and states the verdict in words', async () => {
    router = routeWith({ sets: [SET], capital: CAP(), irrbb: url => url.includes('tier1Capital=3600') ? { ...IRRBB(0.05, true), outlierTest: { ...IRRBB(0.05, true).outlierTest, tier1Capital: 3600, currency: 'CZK', breached: false } } : IRRBB(null, false) })
    await renderPage(<SnapshotIrrbbPage params={Promise.resolve({ id: RUN_ID })} />)
    const source = await screen.findByTestId('tier1-from-capital')
    expect(source.textContent).toMatch(/převzato z výpočtu kapitálu|taken from this snapshot's Pillar 1/)
    expect(source.textContent).toMatch(/bcbs-d424-sa v1/)
    await screen.findByTestId('sot-verdict')
    expect(calls.some(c => c.url.includes('/irrbb') && c.url.includes('tier1Capital=3600'))).toBe(true)
    expect(screen.getByTestId('sot-verdict').textContent).toMatch(/pod prahem 15 %|below the 15 % threshold/)
    // An explicit override is possible, and only then is the input shown.
    expect(screen.queryByLabelText(/^(Tier 1 capital|Kapitál Tier 1)$/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Override|Zadat jinou hodnotu/ }))
    expect(screen.getByLabelText(/^(Tier 1 capital|Kapitál Tier 1)$/)).toBeTruthy()
  })

  it('explains what Tier 1 is for when the run has no own funds to take it from', async () => {
    router = routeWith({ sets: [SET] })
    await renderPage(<SnapshotIrrbbPage params={Promise.resolve({ id: RUN_ID })} />)
    await screen.findByText(/Tier 1 not supplied — the ratio|Tier 1 nezadán/)
    expect(screen.getByText(/změna EVE nad 15 % Tier 1|EVE change above 15 % of Tier 1/)).toBeTruthy()
    // The label names the currency the engine compares Tier 1 in (this EUR-only fixture: EUR).
    expect(screen.getByText(/^(Kapitál Tier 1 v EUR|Tier 1 capital in EUR)$/)).toBeTruthy()
  })

  it('liquidity forecast: same run context and picker, and the survival horizon as a sentence', async () => {
    router = routeWith({ sets: [SET], forecast: FORECAST(12, true) })
    await renderPage(<SnapshotLiquidityForecastPage params={Promise.resolve({ id: RUN_ID })} />)
    const survival = await screen.findByTestId('survival-CZK')
    expect(survival.textContent).toMatch(/Banka vydrží 11 dní při scénáři „behaviorální model nmd-linear-core v1\.0\.0“|The bank survives 11 day\(s\) under the "behavioural model nmd-linear-core v1\.0\.0" scenario/)
    expect(screen.getByTestId('run-subtitle').textContent).not.toContain(RUN_ID)
    expect(calls.some(c => c.url.includes('/liquidity-forecast') && c.url.includes('curveSetId=cs-ref'))).toBe(true)
  })

  it('liquidity forecast with no curve set for the run date shows the call to action, not a forecast', async () => {
    router = routeWith({ sets: [] })
    await renderPage(<SnapshotLiquidityForecastPage params={Promise.resolve({ id: RUN_ID })} />)
    await screen.findByTestId('curve-set-cta')
    expect(calls.some(c => c.url.includes('/liquidity-forecast'))).toBe(false)
  })
})
