// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// IRRBB summary on the snapshot detail page (ADR-0313 phase 1, #11107): ΔEVE / ΔNII per scenario
// in money and % of Tier 1, the outlier badge from the engine's limit status, and every data gap
// the engine reports — flat curve extrapolation above all — listed in Czech next to the figures.
import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { Irrbb } from '@/components/balance-sheet/contracts'
import { irrbbSchema } from '@/components/balance-sheet/contracts'
import { dataGapText, irrbbRows, outlierStatus } from '@/components/balance-sheet/irrbbSummary'
import { IrrbbSummaryPanel } from '@/components/balance-sheet/IrrbbSummaryPanel'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const base = (over: Partial<Irrbb['outlierTest']> = {}, currency = 'CZK'): Irrbb => irrbbSchema.parse({
  runId: 'run-9', asOf: '2026-09-30', provenance: 'synthetic', curveSetId: 'cs-9', curveSetProvenance: 'synthetic', curveSetSource: 'referenční sada',
  gaps: [],
  scenarios: [
    { scenario: 'parallel-up', currencies: [{ currency, basePv: 1000, shockedPv: 800, deltaEve: -200, eveLoss: 200, deltaNii: 50 }], aggregateLoss: 200 },
    { scenario: 'steepener', currencies: [{ currency, basePv: 1000, shockedPv: 990, deltaEve: -10, eveLoss: 10, deltaNii: null }], aggregateLoss: 10 },
  ],
  worstCase: { scenario: 'parallel-up', loss: 200, currency, byCurrency: { [currency]: 'parallel-up' } },
  outlierTest: {
    tier1Supplied: false, tier1Capital: 1000, tier1Source: 'own-funds', tier1Gap: null, currency: 'CZK', threshold: 0.15,
    earlyWarning: 0.12, limitId: 'irrbb-eve-outlier', status: 'BREACH', ratio: 0.2, breached: true, note: 'n', ...over,
  },
  shockNotConfigured: [], unpriced: [],
  dataGaps: [
    { code: 'CURVE_EXTRAPOLATED_FLAT', currency: 'CZK', curveIndex: 'CZEONIA', lastPillarDate: '2027-09-30', lastFlowDate: '2031-09-30', flowsBeyond: 48, basePvBeyond: 500, detail: 'en' },
    { code: 'PREPAYMENT_NOT_MODELLED', count: 3, detail: 'en' },
    { code: 'SOMETHING_NEW', detail: 'engine detail for a new code' },
  ],
  assumptions: {
    model: { id: 'nmd-linear-core', version: '1.0.0' }, shockSizes: [{ currency: 'CZK', parallelBp: 200, shortBp: 250, longBp: 100 }],
    shockSource: '2024/856', shortDecayYears: 4, postShockFloor: null, postShockFloorSource: 'x', nmdRepricing: 'x', floatingRepricing: 'x',
    eveBasis: 'x', niiBasis: 'x', niiHorizonMonths: 12, currencyAggregation: 'x',
  },
})

describe('irrbb summary helpers', () => {
  it('divides by Tier 1 only in the same currency', () => {
    const czk = irrbbRows(base())
    expect(czk[0].eveToTier1).toBeCloseTo(-0.2)
    expect(czk[0].niiToTier1).toBeCloseTo(0.05)
    expect(czk[1].niiToTier1).toBeNull()
    expect(czk[0].worst).toBe(true)
    // A EUR book against a CZK Tier 1: never divided.
    const eur = irrbbRows(base({}, 'EUR'))
    expect(eur[0].eveToTier1).toBeNull()
    expect(eur[0].niiToTier1).toBeNull()
  })

  it('takes the engine status, and never reads a missing figure as OK', () => {
    expect(outlierStatus(base())).toBe('BREACH')
    expect(outlierStatus(base({ status: undefined, ratio: null, breached: null }))).toBe('NOT_EVALUABLE')
    expect(outlierStatus(base({ status: undefined, ratio: 0.05, breached: false }))).toBe('OK')
  })

  it('states flat extrapolation in Czech with its dates, and falls back to the engine detail', () => {
    const gaps = base().dataGaps!
    const flat = dataGapText(gaps[0], 'cs')
    expect(flat).toContain('CZEONIA')
    expect(flat).toContain('2027-09-30')
    expect(flat).toContain('48 toků')
    expect(flat).toMatch(/plochá extrapolace/)
    expect(dataGapText(gaps[1], 'cs')).toMatch(/Předčasné splátky/)
    expect(dataGapText(gaps[2], 'cs')).toBe('engine detail for a new code')
  })
})

describe('IrrbbSummaryPanel', () => {
  let calls: string[] = []
  let response: () => Response
  beforeEach(() => {
    calls = []
    vi.stubGlobal('fetch', vi.fn(async (u: string) => { calls.push(String(u)); return response() }))
  })
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  const renderPanel = async () => {
    await act(async () => { render(<LanguageProvider><IrrbbSummaryPanel runId="run-9" /></LanguageProvider>) })
  }

  it('asks for the engine default curve set and shows scenarios, % of Tier 1, badge and gaps', async () => {
    response = () => json(base())
    await renderPanel()
    await screen.findByText(/Odlehlá banka|Outlier \(breached\)/)
    expect(calls).toEqual(['/api/svc/risk-engine/api/v1/risk/snapshots/run-9/irrbb'])
    expect(screen.getByText(/Paralelní posun nahoru|Parallel up/)).toBeTruthy()
    expect(screen.getAllByText(/−20,00 %|-20.00 %/).length).toBeGreaterThan(0)
    expect(document.querySelectorAll('tr[data-worst="true"]').length).toBe(1)
    expect(document.querySelector('li[data-gap="CURVE_EXTRAPOLATED_FLAT"]')?.textContent).toMatch(/CZEONIA/)
    expect(screen.getByText('engine detail for a new code')).toBeTruthy()
    // No raw run id as prose: it only appears inside the detail link's URL.
    expect(document.body.textContent).not.toContain('run-9')
  })

  it('a NOT_EVALUABLE test is never a green badge and says why', async () => {
    response = () => json(base({ status: 'NOT_EVALUABLE', ratio: null, breached: null, tier1Capital: null, tier1Source: null, tier1Gap: 'Tier 1 is not positive' }))
    await renderPanel()
    await screen.findByText(/Test nelze vyhodnotit|Test not evaluable/)
    expect(screen.getByText(/Tier 1 is not positive/)).toBeTruthy()
    expect(screen.queryByText(/Pod prahem|Below the outlier/)).toBeNull()
  })

  it('no curve set for the run date is shown as unavailable, not as zero risk', async () => {
    response = () => json({ error: 'no curve set is recorded as of 2026-09-30' }, 400)
    await renderPanel()
    expect(screen.queryByRole('table')).toBeNull()
    expect(document.body.textContent).toMatch(/chybí sada výnosových křivek|no curve set as of the snapshot date/)
  })
})
