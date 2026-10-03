// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// #11107 — the snapshot instrument table a risk officer reads: business labels, obligor chip,
// capital join, sort, search, per-currency totals, and "—" with a reason for what is unknown.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import type { Capital, Instrument } from '@/components/balance-sheet/contracts'
import {
  buildRows, capitalByInstrument, exposureClassLabel, filterRows, formatMoney, formatPercent, instrumentReference,
  kindCounts, kindGroupLabel, kindLabel, sortByOutstanding, totalsByCurrency,
} from '@/components/balance-sheet/instruments'

const session = vi.hoisted(() => ({ roles: ['ROLE_RISK'] as string[] }))
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: session.roles } }, status: 'authenticated' }),
}))

import { InstrumentsPanel } from '@/components/balance-sheet/InstrumentsPanel'
import { loanLabel } from '@/components/balance-sheet/instrumentLabels'

const nbsp = (s: string) => s.replace(/[  ]/g, ' ')

const loan = (id: string, outstanding: number, over: Partial<Instrument> = {}): Instrument => ({
  id, kind: 'AMORTISING_LOAN', glAccountCode: '1200', currency: 'CZK', outstanding,
  valueDate: '2025-01-15', maturityDate: '2030-01-15',
  rateTerms: { rateType: 'FIXED', currentAnnualRate: 0.0525, index: null, spread: null, resetFrequencyMonths: null, nextResetDate: null },
  counterpartyRef: `p-${id}`, ifrs9Stage: 'STAGE_1',
  loan: { method: 'ANNUITY', periodsPerYear: 12, remainingPeriods: 48, nextDueDate: '2026-10-15' },
  ...over,
})

const A = loan('aaaaaaaa-0000-4000-8000-000000000001', 100_000)
const B = loan('bbbbbbbb-0000-4000-8000-000000000002', 250_000)
const C = loan('cccccccc-0000-4000-8000-000000000003', 5_000, { currency: 'EUR', kind: 'BULLET', rateTerms: null, maturityDate: null, counterpartyRef: null })

const CAPITAL: Capital = {
  runId: 'run-1', asOf: '2026-09-30', provenance: 'production', parameterSetId: 'eu-crr', parameterSetVersion: '1',
  currencies: [{
    currency: 'CZK', classes: [], totalEad: 350_000, totalRwa: 262_500,
    lines: [
      { exposureClass: 'retail', label: 'x', glAccountCode: '1200', instrumentId: A.id, ead: 100_000, riskWeight: 0.75, rwa: 75_000, factorKey: 'k', citation: 'c' },
      { exposureClass: 'retail', label: 'x', glAccountCode: '1200', instrumentId: B.id, ead: 250_000, riskWeight: 0.75, rwa: 187_500, factorKey: 'k', citation: 'c' },
      { exposureClass: 'bank', label: 'GL', glAccountCode: '1500', instrumentId: null, ead: 9, riskWeight: 0.2, rwa: 1.8, factorKey: 'k', citation: 'c' },
    ],
  }],
  unclassified: [], notes: [],
  assumptions: {
    parameterSetId: 'eu-crr', parameterSetVersion: '1', source: 's', scope: 's', factors: [],
    classification: { retailTreatment: 'other-retail', bankScraGrade: 'C', domesticCurrency: 'CZK', glAccounts: [], glAccountTypes: [], choices: [] },
    exposureValue: 'e', creditRiskMitigation: 'c', offBalanceSheet: 'o', defaulted: 'd', ownFunds: 'o', creditRiskOnly: 'c', currencyAggregation: 'c',
  },
} as unknown as Capital

describe('instrument model', () => {
  it('labels kinds and exposure classes in Czech, keeping unknown values verbatim', () => {
    expect(kindLabel('AMORTISING_LOAN', 'cs')).toBe('Splátkový úvěr')
    expect(kindGroupLabel('AMORTISING_LOAN', 'cs')).toBe('Splátkové úvěry')
    expect(kindLabel('SOMETHING_NEW', 'cs')).toBe('SOMETHING_NEW')
    expect(exposureClassLabel('retail', 'cs')).toBe('Retailové')
    expect(exposureClassLabel('mystery', 'cs')).toBe('mystery')
  })

  it('builds a short readable reference from the id', () => {
    expect(instrumentReference(A, 'cs')).toBe('Úvěr AAAAAAAA')
    expect(instrumentReference({ id: 'x-1', kind: 'BOND' }, 'en')).toBe('Instr. X')
    // #11107: the loan's contract number wins over the id-derived handle when present.
    expect(instrumentReference({ ...A, contractNumber: 'UV-2026-000123' }, 'cs')).toBe('UV-2026-000123')
    expect(instrumentReference({ ...A, contractNumber: null }, 'cs')).toBe('Úvěr AAAAAAAA')
    expect(loanLabel({ ...A, contractNumber: 'UV-2026-000123' }, null, 'en', 'en-GB').label).toMatch(/^UV-2026-000123 · Annuity loan/i)
    expect(loanLabel(A, null, 'en', 'en-GB').label).not.toContain('UV-')
  })

  it('formats cs-CZ money with currency and fractions as percent', () => {
    expect(nbsp(formatMoney(1234567.5, 'CZK', 'cs-CZ'))).toBe('1 234 567,50 Kč')
    expect(nbsp(formatPercent(0.0525, 'cs-CZ'))).toBe('5,25 %')
  })

  it('joins capital per instrument from the currency lines only and sums multi-line instruments', () => {
    const doubled = { ...CAPITAL, currencies: [{ ...CAPITAL.currencies[0], lines: [...CAPITAL.currencies[0].lines, { ...CAPITAL.currencies[0].lines[0], ead: 100_000, riskWeight: 0.35, rwa: 35_000 }] }] } as Capital
    const m = capitalByInstrument(doubled)
    expect(m.get(A.id)).toEqual({ exposureClass: 'retail', ead: 200_000, rwa: 110_000, riskWeight: 0.55 })
    expect(m.size).toBe(2)
  })

  it('sorts by outstanding magnitude, filters by reference/obligor/id and totals per currency', () => {
    const rows = sortByOutstanding(buildRows([A, C, B], 'cs', capitalByInstrument(CAPITAL), new Map([[`p-${A.id}`, 'Jan Novák']])))
    expect(rows.map(r => r.instrument.id)).toEqual([B.id, A.id, C.id])
    expect(filterRows(rows, 'novák').map(r => r.instrument.id)).toEqual([A.id])
    expect(filterRows(rows, 'úvěr bbbb').map(r => r.instrument.id)).toEqual([B.id])
    expect(filterRows(rows, '000000000003').map(r => r.instrument.id)).toEqual([C.id])
    expect(totalsByCurrency(rows)).toEqual([
      { currency: 'CZK', count: 2, outstanding: 350_000, rwa: 262_500, rwaKnown: 2 },
      { currency: 'EUR', count: 1, outstanding: 5_000, rwa: 0, rwaKnown: 0 },
    ])
    expect(kindCounts([A, B, C])).toEqual([['AMORTISING_LOAN', 2], ['BULLET', 1]])
  })
})

describe('InstrumentsPanel', () => {
  let calls: string[] = []
  let capitalStatus = 200
  beforeEach(() => {
    calls = []
    capitalStatus = 200
    session.roles = ['ROLE_RISK']
    vi.stubGlobal('fetch', vi.fn(async (u: string) => {
      calls.push(String(u))
      if (String(u).includes('/capital')) return new Response(JSON.stringify(CAPITAL), { status: capitalStatus })
      if (String(u).includes('/parties/')) return new Response(JSON.stringify({ legalName: 'Jan Novák' }), { status: 200 })
      return new Response('{}', { status: 404 })
    }))
  })
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

  const renderPanel = async () => {
    await act(async () => { render(<InstrumentsPanel runId="run-1" instruments={[A, C, B]} lang="cs" />) })
  }

  it('renders readable rows, Czech group counts, RWA and totals — and no party lookup without parties:view', async () => {
    session.roles = ['ROLE_FINANCE'] // balance-sheet:view without parties:view-detail
    await renderPanel()
    expect(screen.getByTestId('instrument-kind-counts').textContent).toBe('Splátkové úvěry: 2 · Jednorázově splatné úvěry: 1')
    const rows = document.querySelectorAll('tbody tr[data-instrument-id]')
    expect([...rows].map(r => r.getAttribute('data-instrument-id'))).toEqual([B.id, A.id, C.id])
    const first = within(rows[0] as HTMLElement)
    expect(first.getByText('Úvěr BBBBBBBB')).toBeTruthy()
    expect(first.getByText('Retailové')).toBeTruthy()
    expect(nbsp(rows[0].textContent ?? '')).toContain('187 500,00 Kč')
    expect(nbsp(rows[0].textContent ?? '')).toContain('5,25 %')
    expect(rows[0].textContent).not.toContain(B.id)
    expect(nbsp(screen.getByTestId('instrument-total-CZK').textContent ?? '')).toContain('350 000,00 Kč')
    expect(calls.some(u => u.includes('/parties/'))).toBe(false)
    expect(screen.getByText(/Jména dlužníků vyžadují oprávnění/)).toBeTruthy()
  })

  it('looks obligor names up for ROLE_RISK (party detail only, never the directory)', async () => {
    session.roles = ['ROLE_RISK']
    await renderPanel()
    expect((await screen.findAllByText('Jan Novák')).length).toBe(2)
    expect(calls.filter(u => u.includes('/parties/')).length).toBe(2)
    expect(calls.some(u => /\/parties(\/search)?(\?|$)/.test(u))).toBe(false)
  })

  it('explains every unknown value instead of drawing a zero', async () => {
    capitalStatus = 503
    await renderPanel()
    const eur = document.querySelector(`tr[data-instrument-id="${C.id}"]`) as HTMLElement
    const dashes = within(eur).getAllByText('—')
    expect(dashes.length).toBeGreaterThanOrEqual(5)
    expect(dashes.every(d => (d.getAttribute('title') ?? '').length > 0)).toBe(true)
    expect(dashes.some(d => /Kapitálový výpočet snímku není dostupný/.test(d.getAttribute('title') ?? ''))).toBe(true)
  })

  it('resolves obligor names with parties:view, searches by them, and shows the UUID only in the detail', async () => {
    session.roles = ['ROLE_ADMIN']
    await renderPanel()
    expect((await screen.findAllByText('Jan Novák')).length).toBe(2)
    expect(calls.filter(u => u.includes('/parties/')).length).toBe(2)
    fireEvent.change(screen.getByLabelText('Hledat nástroje'), { target: { value: 'AAAAAAAA' } })
    expect(document.querySelectorAll('tbody tr[data-instrument-id]').length).toBe(1)
    fireEvent.click(screen.getByText('Úvěr AAAAAAAA'))
    expect(within(screen.getByTestId('instrument-detail')).getByText(A.id)).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Hledat nástroje'), { target: { value: 'nic takového' } })
    expect(screen.getByText('Hledání neodpovídá žádný nástroj.')).toBeTruthy()
  })
})
