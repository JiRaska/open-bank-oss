// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The liquidity and capital pages read by regulatory CATEGORY, in Czech, with no raw UUID as the
// text a person reads, and an empty HQLA stock shown as a configuration gap rather than "0 %".

import React, { Suspense } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { formatMoney, groupExposures, groupLiquidityLines } from '@/lib/risk/aggregate'
import { categoryCountText, exposureClassLabel, ifrs9StageLabel, liquidityCategoryLabel, plainCitation, ratiosNotComputableText } from '@/lib/risk/labels'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { id: 'sub-1', name: 'Jana', roles: ['ROLE_FINANCE'], accessToken: 'h.e30.s' } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

import SnapshotLiquidityPage from '@/app/balance-sheet/snapshots/[id]/liquidity/page'
import SnapshotCapitalPage from '@/app/balance-sheet/snapshots/[id]/capital/page'

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i
const LOANS = ['8169e954-b42d-4bab-be5f-4c160263f098', '019fb944-6c3a-7bd2-814d-7c946371f1ae', '24977cca-20b2-4877-80d1-403b40181a89']
const EU_LOAN = 'EU 2015/61 Art. 32(3)(a) (monies due from non-financial customers, 50%)'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const renderCs = async (node: React.ReactNode) => {
  await act(async () => { render(<LanguageProvider initialLanguage="cs"><Suspense fallback={null}>{node}</Suspense></LanguageProvider>) })
}

describe('shared risk labels', () => {
  it('renders an EU citation as a Czech article reference, without the English explanation', () => {
    expect(plainCitation(EU_LOAN, 'cs').article).toBe('čl. 32 odst. 3 písm. a) nařízení 2015/61')
    expect(plainCitation(EU_LOAN, 'en').article).toBe('Regulation 2015/61 Art. 32(3)(a)')
  })

  it('turns an UNVERIFIED marker into a flag, never into text', () => {
    const c = plainCitation('CRR Art. 122 (corporate without a credit assessment by a nominated ECAI: 100%; paragraph UNVERIFIED)', 'cs')
    expect(c.article).toBe('čl. 122 nařízení CRR (575/2013)')
    expect(c.unverified).toBe(true)
    expect(c.article).not.toMatch(/UNVERIFIED/)
    expect(plainCitation('BCBS d238 ¶153 (retail / small business inflows, 50%)', 'cs')).toMatchObject({ article: 'BCBS d238, odst. 153', unverified: false })
  })

  it('names classes, stages and categories in Czech and falls back to the key for an unknown one', () => {
    expect(exposureClassLabel('bank', 'cs')).toBe('Instituce')
    expect(exposureClassLabel('corporate', 'cs')).toBe('Podniky')
    expect(exposureClassLabel('other-asset', 'cs')).toBe('Ostatní aktiva')
    expect(exposureClassLabel('brand-new-class', 'cs')).toBe('brand-new-class')
    expect(ifrs9StageLabel('STAGE_1', 'cs')).toBe('Fáze 1 — bez významného zhoršení')
    expect(liquidityCategoryLabel('lcr-retail-loan-inflow', 'cs')).toBe('Splátky úvěrů nefinančním klientům splatné do 30 dní')
    expect(ratiosNotComputableText('multi-currency', 'cs')).toMatch(/více měn/)
    expect(ratiosNotComputableText('unknown', 'cs')).toBeNull()
  })

  it('agrees Czech counts', () => {
    expect(categoryCountText({ count: 1, unit: 'loans' }, 'cs')).toBe('1 úvěr')
    expect(categoryCountText({ count: 3, unit: 'loans' }, 'cs')).toBe('3 úvěry')
    expect(categoryCountText({ count: 44, unit: 'loans' }, 'cs')).toBe('44 úvěrů')
    expect(categoryCountText({ count: 62, unit: 'accounts' }, 'cs')).toBe('62 klientských účtů')
  })

  it('formats money with grouping, two decimals and the currency', () => {
    expect(formatMoney(582088.6, 'cs-CZ', 'CZK')).toMatch(/^582\s088,60\sKč$/)
  })
})

const loanLine = (id: string, amount: number) => ({
  label: `Loan ${id}: contractual payments due ≤ 30 days`, glAccountCode: '1300', amount, factor: 0.5,
  factorKey: 'lcr-retail-loan-inflow', weighted: amount * 0.5, citation: EU_LOAN, instrumentId: id, itemCount: 1,
})

describe('aggregation by regulatory category', () => {
  it('collapses one line per loan into one category whose totals reconcile', () => {
    const lines = [loanLine(LOANS[0], 100), loanLine(LOANS[1], 200), loanLine(LOANS[2], 300),
      { label: 'GL 1001 (deposit-at-fi-operational)', glAccountCode: '1001', amount: 50, factor: 0, factorKey: 'lcr-operational-deposit-inflow', weighted: 0, citation: 'x' }]
    const groups = groupLiquidityLines(lines)
    expect(groups).toHaveLength(2)
    expect(groups[0]).toMatchObject({ factorKey: 'lcr-retail-loan-inflow', count: 3, unit: 'loans', amount: 600, weighted: 300 })
    expect(groups[1]).toMatchObject({ unit: 'glAccounts', count: 1 })
    expect(groups.reduce((a, g) => a + g.weighted, 0)).toBe(lines.reduce((a, l) => a + l.weighted, 0))
  })

  it('counts customer accounts for an aggregated deposit line', () => {
    const [g] = groupLiquidityLines([{ label: 'Retail deposits, less stable (62 customer accounts)', amount: 1, factor: 0.1, factorKey: 'lcr-retail-less-stable-runoff', weighted: 0.1, citation: 'x', itemCount: 62 }])
    expect(g).toMatchObject({ unit: 'accounts', count: 62 })
  })

  it('groups exposures by class and IFRS 9 stage', () => {
    const e = (id: string, stage: string, ead: number) => ({ exposureClass: 'corporate', label: `Loan ${id} (${stage})`, instrumentId: id, ead, riskWeight: 1, rwa: ead, factorKey: 'rw-corporate-unrated', citation: 'CRR Art. 122', ifrs9Stage: stage })
    const groups = groupExposures([e(LOANS[0], 'STAGE_1', 10), e(LOANS[1], 'STAGE_1', 20), e(LOANS[2], 'STAGE_2', 5)])
    expect(groups.map(g => [g.ifrs9Stage, g.count, g.rwa])).toEqual([['STAGE_1', 2, 30], ['STAGE_2', 1, 5]])
  })
})

const hqlaEmpty = { lines: [], level1: 0, level2a: 0, level2b: 0, adjustmentFor15Cap: 0, adjustmentFor40Cap: 0, level2bCapBinding: false, level2CapBinding: false, stock: 0 }
const LIQ = {
  runId: 'run-9', asOf: '2026-09-30', provenance: 'synthetic', parameterSetId: 'eu-2015-61', parameterSetVersion: '2',
  currencies: [{
    currency: 'CZK',
    lcr: {
      hqla: hqlaEmpty,
      outflows: [{ label: 'Retail deposits, less stable (62 customer accounts)', glAccountCode: null, amount: 1000, factor: 0.1, factorKey: 'lcr-retail-less-stable-runoff', weighted: 100, citation: 'EU 2015/61 Art. 25(1) (other retail deposits, 10%)', instrumentId: null, itemCount: 62 }],
      inflows: LOANS.map((id, i) => loanLine(id, 100 * (i + 1))),
      totalOutflows: 100, totalInflows: 300, inflowCap: 75, cappedInflows: 75, inflowCapBinding: true, netOutflows: 25, ratio: 0,
    },
    nsfr: { asf: [], rsf: [], totalAsf: 0, totalRsf: 0, ratio: null },
  }],
  total: null,
  unclassified: [], notes: [],
  assumptions: {
    parameterSetId: 'eu-2015-61', parameterSetVersion: '2', source: 's', scope: 'scope', factors: [],
    classification: {
      retailStableShare: 0, operationalDepositShare: 0, tier2OverOneYearShare: 0, loansQualifyForLowRiskWeight: false,
      glAccounts: [{ key: '1510', glClass: 'hqla-l1-cash-or-reserves', description: 'Level 1' }], glAccountTypes: [], choices: [],
    },
    hqlaCapMethod: 'a', loanInflows: 'b', loanRsf: 'c', notInData: 'd', currencyAggregation: 'e',
  },
}
const INSTRUMENTS = {
  runId: 'run-9', asOf: '2026-09-30',
  instruments: LOANS.map(id => ({
    id, kind: 'LOAN', glAccountCode: '1300', currency: 'CZK', outstanding: 138490.82, valueDate: '2025-05-03', maturityDate: '2029-05-03',
    rateTerms: null, counterpartyRef: null, ifrs9Stage: 'STAGE_1', loan: { method: 'ANNUITY', periodsPerYear: 12, remainingPeriods: 31, nextDueDate: '2026-10-03' },
  })),
}

let router: (url: string) => Response
beforeEach(() => { vi.stubGlobal('fetch', vi.fn(async (u: string) => router(String(u)))) })
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('Liquidity page reads like a report, not a dump', () => {
  it('says LCR cannot be computed when the HQLA stock is empty, and where HQLA is configured — never 0 %', async () => {
    router = u => json(u.endsWith('/instruments') ? INSTRUMENTS : LIQ)
    await renderCs(<SnapshotLiquidityPage params={Promise.resolve({ id: 'run-9' })} />)
    const lcr = await screen.findByTestId('lcr-CZK')
    expect(lcr.getAttribute('data-lcr-state')).toBe('hqla-missing')
    expect(lcr.textContent).toMatch(/LCR nelze spočítat/)
    expect(lcr.textContent).not.toMatch(/^\s*0\s*%/)
    expect(lcr.textContent).toContain('1510')
    expect(lcr.textContent).toContain('openbank.risk.liquidity.classification.gl-accounts')
  })

  it('shows one category row per regulatory category, with a count, and no UUID in the visible text', async () => {
    router = u => json(u.endsWith('/instruments') ? INSTRUMENTS : LIQ)
    await renderCs(<SnapshotLiquidityPage params={Promise.resolve({ id: 'run-9' })} />)
    await screen.findByTestId('lcr-CZK')
    const row = document.querySelector('tr[data-category="lcr-retail-loan-inflow"]')!
    expect(row.textContent).toContain('Splátky úvěrů nefinančním klientům splatné do 30 dní')
    expect(row.textContent).toContain('3 úvěry')
    expect(row.textContent).toContain('čl. 32 odst. 3 písm. a) nařízení 2015/61')
    expect(row.textContent).toMatch(/600,00\sKč/)
    expect(document.querySelectorAll('tr[data-item-of]').length).toBe(0)
    expect(document.querySelector('tr[data-category="lcr-retail-less-stable-runoff"]')!.textContent).toContain('62 klientských účtů')
    expect(document.body.textContent).not.toMatch(UUID)
    expect(document.body.textContent).not.toMatch(/contractual payments due/)

    await act(async () => { fireEvent.click(row.querySelector('button')!) })
    const items = document.querySelectorAll('tr[data-item-of="lcr-retail-loan-inflow"]')
    expect(items.length).toBe(3)
    expect(items[0].textContent).toMatch(/Anuitní úvěr, splatnost/)
    // The id is still on the page — shortened and copyable, never as the label.
    expect(items[0].querySelector('span.mono[title]')!.getAttribute('title')).toMatch(UUID)
    expect(document.body.textContent).not.toMatch(UUID)
  })
})

describe('Capital page reads by class and stage', () => {
  const exposure = (id: string, stage: string) => ({
    exposureClass: 'corporate', label: `Loan ${id} (${stage})`, glAccountCode: '1300', instrumentId: id, ead: 1000, riskWeight: 1, rwa: 1000,
    factorKey: 'rw-corporate-unrated', citation: 'CRR Art. 122 (corporate without a credit assessment by a nominated ECAI: 100%; paragraph UNVERIFIED)', ifrs9Stage: stage,
  })
  const CZK = {
    currency: 'CZK',
    classes: [{ exposureClass: 'corporate', ead: 3000, rwa: 3000, citations: [exposure(LOANS[0], 'STAGE_1').citation] }],
    lines: LOANS.map(id => exposure(id, 'STAGE_1')), totalEad: 3000, totalRwa: 3000, ownFunds: null,
  }
  const CAP = {
    runId: 'run-9', asOf: '2026-09-30', provenance: 'synthetic', parameterSetId: 'crr', parameterSetVersion: '1',
    currencies: [CZK, { ...CZK, currency: 'EUR' }], total: null, ownFundsRequirement: null, ratios: null,
    ratiosNotComputable: 'multi-currency book: own funds are not converted to one currency', ratiosNotComputableCode: 'multi-currency',
    unclassified: [], notes: ['credit-risk only: UPPER BOUND'],
    assumptions: {
      parameterSetId: 'crr', parameterSetVersion: '1', source: 's', scope: 'scope', factors: [],
      classification: { retailTreatment: 'r', bankScraGrade: 'C', domesticCurrency: 'CZK', glAccounts: [], glAccountTypes: [], choices: [] },
      exposureValue: 'e', creditRiskMitigation: 'c', offBalanceSheet: 'o', defaulted: 'd', ownFunds: 'f', creditRiskOnly: 'credit-risk only: UPPER BOUND', currencyAggregation: 'g',
    },
  }

  it('aggregates loans by class and stage, in Czech, without UUIDs or UNVERIFIED markers, and explains the missing ratios', async () => {
    router = u => json(u.endsWith('/instruments') ? INSTRUMENTS : CAP)
    await renderCs(<SnapshotCapitalPage params={Promise.resolve({ id: 'run-9' })} />)
    await screen.findByTestId('ratios-not-computable')
    expect(screen.getByTestId('ratios-not-computable').textContent).toMatch(/více měn/)
    expect(screen.getByTestId('ratios-not-computable').textContent).not.toMatch(/multi-currency book/)
    const rows = document.querySelectorAll('tr[data-category]')
    expect(rows.length).toBe(2) // one per currency section
    expect(rows[0].textContent).toContain('Podniky · Fáze 1 — bez významného zhoršení')
    expect(rows[0].textContent).toContain('3 úvěry')
    expect(document.querySelector('tr[data-class="corporate"]')!.textContent).toContain('Podniky')
    const visible = document.body.textContent ?? ''
    expect(visible).not.toMatch(UUID)
    expect(visible).not.toMatch(/UNVERIFIED/)
  })
})
