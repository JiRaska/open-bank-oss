// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Group the risk-engine's per-contract lines into the regulatory CATEGORIES a reader reasons in.
// The engine reports one line per loan (that is what makes it auditable); a person reads "44 loans
// due within 30 days, 582 088,60 CZK, weighted 50 %", and opens the 44 only when they need to.
// Sums are of the engine's own figures — nothing is recomputed, so a category total always
// reconciles to the engine's total line.

import { formatMoneyExact } from '@/lib/format/money'

export type LiquidityLineLike = {
  label: string
  glAccountCode?: string | null
  amount: number
  factor?: number | null
  factorKey?: string | null
  weighted: number
  citation: string
  instrumentId?: string | null
  itemCount?: number | null
}

export type CountUnit = 'loans' | 'accounts' | 'glAccounts'

export type LiquidityCategory<L extends LiquidityLineLike = LiquidityLineLike> = {
  key: string
  factorKey: string | null
  factor: number | null
  amount: number
  weighted: number
  count: number
  unit: CountUnit
  citation: string
  items: L[]
}

export function groupLiquidityLines<L extends LiquidityLineLike>(lines: L[]): LiquidityCategory<L>[] {
  const groups = new Map<string, LiquidityCategory<L>>()
  for (const l of lines) {
    const key = l.factorKey ?? `unweighted:${l.citation}`
    let g = groups.get(key)
    if (!g) {
      g = { key, factorKey: l.factorKey ?? null, factor: l.factor ?? null, amount: 0, weighted: 0, count: 0, unit: 'glAccounts', citation: l.citation, items: [] }
      groups.set(key, g)
    }
    g.amount += l.amount
    g.weighted += l.weighted
    g.items.push(l)
  }
  for (const g of groups.values()) {
    const loans = new Set(g.items.map(i => i.instrumentId).filter((x): x is string => !!x))
    if (loans.size > 0) {
      g.unit = 'loans'
      g.count = loans.size
    } else if (g.items.some(i => typeof i.itemCount === 'number')) {
      g.unit = 'accounts'
      g.count = Math.max(...g.items.map(i => i.itemCount ?? 0))
    } else {
      g.unit = 'glAccounts'
      g.count = new Set(g.items.map(i => i.glAccountCode ?? i.label)).size
    }
  }
  // Largest weighted contribution first: the row that moves the ratio is the row read first.
  return [...groups.values()].sort((a, b) => b.weighted - a.weighted || b.amount - a.amount)
}

export type ExposureLineLike = {
  exposureClass: string
  label: string
  glAccountCode?: string | null
  instrumentId?: string | null
  ead: number
  riskWeight: number
  rwa: number
  factorKey: string
  citation: string
  ifrs9Stage?: string | null
}

export type ExposureGroup<L extends ExposureLineLike = ExposureLineLike> = {
  key: string
  exposureClass: string
  ifrs9Stage: string | null
  riskWeight: number
  ead: number
  rwa: number
  count: number
  unit: CountUnit
  citation: string
  items: L[]
}

export function groupExposures<L extends ExposureLineLike>(lines: L[]): ExposureGroup<L>[] {
  const groups = new Map<string, ExposureGroup<L>>()
  for (const l of lines) {
    const stage = l.ifrs9Stage ?? null
    const key = `${l.exposureClass}|${stage ?? ''}|${l.factorKey}`
    let g = groups.get(key)
    if (!g) {
      g = { key, exposureClass: l.exposureClass, ifrs9Stage: stage, riskWeight: l.riskWeight, ead: 0, rwa: 0, count: 0, unit: 'glAccounts', citation: l.citation, items: [] }
      groups.set(key, g)
    }
    g.ead += l.ead
    g.rwa += l.rwa
    g.items.push(l)
  }
  for (const g of groups.values()) {
    const loans = new Set(g.items.map(i => i.instrumentId).filter((x): x is string => !!x))
    g.unit = loans.size > 0 ? 'loans' : 'glAccounts'
    g.count = loans.size > 0 ? loans.size : g.items.length
  }
  return [...groups.values()].sort((a, b) => b.rwa - a.rwa || b.ead - a.ead)
}

/** Exact money for the regulatory tables (reuses the shared `formatMoneyExact`); no currency → bare number. */
export function formatMoney(v: number, locale: string, currency?: string | null): string {
  return currency ? formatMoneyExact(v, currency, locale) : v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function formatPercent(v: number, locale: string): string {
  return `${(v * 100).toLocaleString(locale, { maximumFractionDigits: 2 })} %`
}
