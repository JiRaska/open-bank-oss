// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * The three-calendar-month ČNB reference-mid trend (issue #7735). This module is the admin-ui
 * mirror of `mapFxHistoryList`/`fxRateHistory` in openbank-customer-edge's `CustomerEdgeResource.kt` —
 * SAME windowing (by calendar date, never by row count) and SAME normalization (validate, dedup
 * by date keeping the latest observation, chronological ascending, inverse-pair support) so the
 * admin portal and the customer app render consistent data for the same pair, by construction.
 */

export interface CnbTrendPoint {
  date: string // yyyy-MM-dd
  rate: string
  timestamp: string
}

export interface FxTrend {
  indicative: true
  base: string
  quote: string
  points: CnbTrendPoint[]
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

  const points: CnbTrendPoint[] = Array.from(byDate.entries())
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([date, v]) => ({ date, rate: String(v.rate), timestamp: v.timestamp }))

  return { indicative: true, base, quote, points }
}

/* ------------------------------------------------------------------------------------------------
 * Presentation helpers for the FX trend chart.
 *
 * Deliberately a SECOND point type. [CnbTrendPoint] above is the wire shape — a decimal kept as a
 * string, because that is how fx-service sends a rate and turning it into a JS number at the edge
 * would lose that guarantee for no reason. [FxTrendPoint] here is what the chart maths needs, so it
 * carries a number. Same file, two layers, one direction of travel: normalise, then render.
 *
 * [normaliseFxTrend] and [buildCnbTrend] overlap on the dedup-by-UTC-day rule and are NOT merged
 * here on purpose: they serve different routes with different upstreams, and folding them together
 * is a change worth reviewing on its own rather than smuggling into a conflict resolution.
 * ---------------------------------------------------------------------------------------------- */

export interface FxTrendPoint { timestamp: string; rate: number }

export interface FxTrendSummary {
  first: FxTrendPoint
  last: FxTrendPoint
  minimum: FxTrendPoint
  maximum: FxTrendPoint
  changePercent: number
}

export type FxTrendDirection = 'up' | 'down' | 'flat'

export function fxTrendDirection(changePercent: number): FxTrendDirection {
  if (changePercent > 0) return 'up'
  if (changePercent < 0) return 'down'
  return 'flat'
}

/** Position fixings on the real elapsed-time axis instead of spacing sparse dates evenly. */
export function fxTrendTimelinePositions(points: FxTrendPoint[]): number[] {
  if (points.length < 2) return points.map(() => 0)
  const start = Date.parse(points[0].timestamp)
  const end = Date.parse(points.at(-1)!.timestamp)
  const span = end - start
  if (!Number.isFinite(span) || span <= 0) return points.map((_, index) => index / (points.length - 1))
  return points.map(point => (Date.parse(point.timestamp) - start) / span)
}

/** Normalise a newest/oldest/mixed API response into one latest valid fixing per UTC day, oldest first. */
export function normaliseFxTrend(rows: unknown[]): FxTrendPoint[] {
  const latestByDate = new Map<string, FxTrendPoint>()
  for (const value of rows) {
    if (!value || typeof value !== 'object') continue
    const row = value as Record<string, unknown>
    const timestamp = typeof row.timestamp === 'string' ? row.timestamp :
      typeof row.validFrom === 'string' ? row.validFrom : ''
    const rawRate = row.rate ?? row.midRate
    const rate = typeof rawRate === 'number' ? rawRate : Number(rawRate)
    if (!timestamp || !Number.isFinite(Date.parse(timestamp)) || !Number.isFinite(rate) || rate <= 0) continue
    const date = new Date(timestamp).toISOString().slice(0, 10)
    const previous = latestByDate.get(date)
    if (!previous || Date.parse(timestamp) > Date.parse(previous.timestamp)) {
      latestByDate.set(date, { timestamp, rate })
    }
  }
  return [...latestByDate.values()].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
}

export function fxTrendChange(points: FxTrendPoint[]): number | null {
  if (points.length < 2 || points[0].rate === 0) return null
  return ((points.at(-1)!.rate - points[0].rate) / points[0].rate) * 100
}

export function fxTrendSummary(points: FxTrendPoint[]): FxTrendSummary | null {
  const changePercent = fxTrendChange(points)
  if (changePercent === null) return null
  return {
    first: points[0],
    last: points.at(-1)!,
    minimum: points.reduce((minimum, point) => point.rate < minimum.rate ? point : minimum),
    maximum: points.reduce((maximum, point) => point.rate > maximum.rate ? point : maximum),
    changePercent,
  }
}
