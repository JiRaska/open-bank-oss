// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * The three-calendar-month ČNB reference-mid trend (issue #7735). This module is the admin-ui
 * mirror of `mapCnbTrend`/`fxRateHistory` in openbank-customer-edge's `CustomerEdgeResource.kt` —
 * SAME windowing (by calendar date, never by row count) and SAME normalization (validate, dedup
 * by date keeping the latest observation, chronological ascending, inverse-pair support) so the
 * admin portal and the customer app render consistent data for the same pair, by construction.
 */

export interface FxTrendPoint {
  date: string // yyyy-MM-dd
  rate: string
  timestamp: string
}

export interface FxTrend {
  indicative: true
  base: string
  quote: string
  points: FxTrendPoint[]
}

interface UpstreamHistoryRow {
  baseCurrency?: string
  quoteCurrency?: string
  source?: string
  midRate?: string | number
  bidRate?: string | number
  askRate?: string | number
  rate?: string | number
  validFrom?: string
  createdAt?: string
}

function toNumber(v: string | number | undefined): number | null {
  if (v === undefined || v === null) return null
  const n = typeof v === 'number' ? v : parseFloat(v)
  return Number.isFinite(n) ? n : null
}

function midOf(bid: number | null, ask: number | null): number | null {
  if (bid !== null && ask !== null) return (bid + ask) / 2
  return ask ?? bid
}

/** Three calendar months before `to` (by DATE, not a row-count approximation), through `to`. */
export function defaultTrendWindow(to: Date = new Date()): { from: string; to: string } {
  const from = new Date(to)
  from.setUTCMonth(from.getUTCMonth() - 3)
  return { from: from.toISOString(), to: to.toISOString() }
}

/**
 * Normalize raw fx-service CNB-history rows into a chronological, deduped trend for [base]/[quote].
 *
 * - a row is DROPPED unless it has both currency codes (when not [inverted]), a usable rate
 *   (midRate, else bid/ask mid, else `rate`, always > 0) and a parseable timestamp;
 * - when [inverted] is true (the direct pair had no history and the swapped pair was fetched
 *   instead) every rate is replaced with its reciprocal, and the output base/quote is the
 *   ORIGINALLY requested pair, not the upstream row's;
 * - rows are DEDUPED by calendar date — the row with the latest timestamp for a date wins;
 * - the result is sorted ascending by date.
 */
export function buildCnbTrend(
  rows: UpstreamHistoryRow[],
  base: string,
  quote: string,
  inverted: boolean,
): FxTrend {
  const byDate = new Map<string, { timestamp: string; rate: number }>()
  for (const row of rows) {
    const rowBase = row.baseCurrency
    const rowQuote = row.quoteCurrency
    if (!rowBase || !rowQuote) continue
    if (!inverted && (rowBase !== base || rowQuote !== quote)) continue

    let rate = toNumber(row.midRate) ?? midOf(toNumber(row.bidRate), toNumber(row.askRate)) ?? toNumber(row.rate)
    if (rate === null || rate <= 0) continue

    const ts = row.validFrom ?? row.createdAt
    if (!ts) continue
    const parsed = Date.parse(ts)
    if (Number.isNaN(parsed)) continue

    if (inverted) rate = 1 / rate

    const date = ts.slice(0, 10)
    const existing = byDate.get(date)
    if (!existing || parsed > Date.parse(existing.timestamp)) {
      byDate.set(date, { timestamp: ts, rate })
    }
  }

  const points: FxTrendPoint[] = Array.from(byDate.entries())
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([date, v]) => ({ date, rate: String(v.rate), timestamp: v.timestamp }))

  return { indicative: true, base, quote, points }
}
