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
import SnapshotDetailPage from '@/app/balance-sheet/snapshots/[id]/page'
import SnapshotIrrbbPage from '@/app/balance-sheet/snapshots/[id]/irrbb/page'
import SnapshotsPage from '@/app/balance-sheet/snapshots/page'

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
