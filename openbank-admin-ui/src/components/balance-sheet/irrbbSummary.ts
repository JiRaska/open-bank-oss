// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pure helpers behind the IRRBB summary panel on the snapshot detail page (ADR-0313 phase 1).
// Kept free of React so the arithmetic and the wording are unit-tested on their own.
//
// HONESTY RULES
//   - "% of Tier 1" is shown only where the figure and Tier 1 are in the SAME currency; a EUR
//     ΔEVE is never divided by a CZK Tier 1.
//   - An outlier status the engine could not evaluate is NOT_EVALUABLE, never a green badge.
//   - Every data gap the engine reports is listed; an unknown code falls back to its own detail.

import type { Irrbb, IrrbbDataGap, IrrbbTreasury, ScenarioName } from './contracts'

export type Lang = 'cs' | 'en'

export const SCENARIO_NAMES: Record<ScenarioName, [string, string]> = {
  'parallel-up': ['Paralelní posun nahoru', 'Parallel up'],
  'parallel-down': ['Paralelní posun dolů', 'Parallel down'],
  steepener: ['Zestrmění', 'Steepener'],
  flattener: ['Zploštění', 'Flattener'],
  'short-up': ['Krátké sazby nahoru', 'Short rates up'],
  'short-down': ['Krátké sazby dolů', 'Short rates down'],
}

export type IrrbbRow = {
  scenario: ScenarioName
  currency: string
  deltaEve: number
  deltaNii: number | null
  /** ΔEVE / Tier 1, null unless Tier 1 is known in this row's currency. */
  eveToTier1: number | null
  niiToTier1: number | null
  worst: boolean
}

export function irrbbRows(data: Irrbb): IrrbbRow[] {
  const tier1 = data.outlierTest.tier1Capital ?? null
  const tier1Currency = data.outlierTest.currency ?? null
  return data.scenarios.flatMap(s => s.currencies.map(c => {
    const comparable = tier1 !== null && tier1 > 0 && tier1Currency === c.currency
    const nii = c.deltaNii ?? null
    return {
      scenario: s.scenario,
      currency: c.currency,
      deltaEve: c.deltaEve,
      deltaNii: nii,
      eveToTier1: comparable ? c.deltaEve / tier1 : null,
      niiToTier1: comparable && nii !== null ? nii / tier1 : null,
      worst: data.worstCase.scenario === s.scenario || data.worstCase.byCurrency[c.currency] === s.scenario,
    }
  }))
}

export type OutlierStatus = 'OK' | 'EARLY_WARNING' | 'BREACH' | 'NOT_EVALUABLE'

/** The engine's status; an older engine without one is derived from `breached`, never assumed OK. */
export function outlierStatus(data: Irrbb): OutlierStatus {
  const o = data.outlierTest
  if (o.status) return o.status
  if (o.ratio === null || o.ratio === undefined || o.breached === null || o.breached === undefined) return 'NOT_EVALUABLE'
  return o.breached ? 'BREACH' : 'OK'
}

export function dataGapText(gap: IrrbbDataGap, lang: Lang): string {
  const cs = lang === 'cs'
  switch (gap.code) {
    case 'CURVE_EXTRAPOLATED_FLAT':
      if (gap.flowsBeyond !== null && gap.flowsBeyond !== undefined) {
        return cs
          ? `Křivka ${gap.curveIndex ?? ''} (${gap.currency ?? ''}) končí ${gap.lastPillarDate ?? '—'}; ${gap.flowsBeyond} toků až do ${gap.lastFlowDate ?? '—'} se diskontuje poslední sazbou drženou konstantní (plochá extrapolace). Šoky se přesto aplikují podle splatnosti každého toku.`
          : `Curve ${gap.curveIndex ?? ''} (${gap.currency ?? ''}) ends ${gap.lastPillarDate ?? '—'}; ${gap.flowsBeyond} flows up to ${gap.lastFlowDate ?? '—'} are discounted at its last rate held flat (flat extrapolation). Shocks are still applied at each flow's own tenor.`
      }
      return cs
        ? `Křivka ${gap.curveIndex ?? ''} (${gap.currency ?? ''}) končí ${gap.lastPillarDate ?? '—'}; pozdější fixace pohyblivých úvěrů se projektují z poslední sazby (plochá extrapolace).`
        : `Curve ${gap.curveIndex ?? ''} (${gap.currency ?? ''}) ends ${gap.lastPillarDate ?? '—'}; later floating fixings are projected from its last rate (flat extrapolation).`
    case 'PREPAYMENT_NOT_MODELLED':
      return cs
        ? 'Předčasné splátky úvěrů nejsou modelovány: úvěry se splácejí podle smluvního kalendáře.'
        : 'Loan prepayment is not modelled: loans run off on their contractual schedule.'
    case 'NMD_BEHAVIOUR_SIMPLIFIED':
      return cs
        ? 'Vklady bez splatnosti používají zjednodušený lineární model (jádro a volatilní část), bez citlivosti sazeb vkladů (beta) a bez kalibrace na vlastní historii banky.'
        : 'Non-maturity deposits use a simplified linear core/volatile model, with no deposit beta and no calibration to the bank’s own history.'
    case 'COMMERCIAL_MARGIN_INCLUDED':
      return cs
        ? 'Toky úvěrů se diskontují včetně obchodní marže; marže se z EVE nevylučuje.'
        : 'Loan flows are discounted including the commercial margin; it is not stripped out of EVE.'
    case 'INSTRUMENTS_NOT_PROJECTED':
      return cs
        ? `Nástroje, které projekce nečte (${gap.count ?? '?'}), nejsou v mezeře přecenění, EVE ani NII zahrnuty: ${gap.detail}`
        : `Instruments the projection does not read (${gap.count ?? '?'}) are not in the repricing gap, EVE or NII: ${gap.detail}`
    default:
      return gap.detail
  }
}

export type TreasuryRow = {
  currency: string
  deals: number
  placements: number
  borrowings: number
  /** The deals' ΔEVE under the worst scenario of their currency (else parallel up); already in the totals. */
  scenario: ScenarioName | null
  deltaEve: number | null
}

/** One row per currency with treasury deals: their share of the figures above, never added to them. */
export function treasuryRows(data: Irrbb): TreasuryRow[] {
  return (data.treasury ?? []).map((t: IrrbbTreasury) => {
    const scenario = data.worstCase.byCurrency[t.currency] ?? (t.scenarios.length > 0 ? 'parallel-up' : null)
    const hit = scenario ? t.scenarios.find(s => s.scenario === scenario) : undefined
    return {
      currency: t.currency,
      deals: t.deals,
      placements: t.placements,
      borrowings: t.borrowings,
      scenario: hit ? scenario : null,
      deltaEve: hit ? hit.deltaEve : null,
    }
  })
}
