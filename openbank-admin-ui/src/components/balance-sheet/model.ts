// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pure derivations for the "Balance sheet & risk" section (#10618). No React, no fetch — every
// rule the pages rely on is here so it can be unit-tested against the wire contracts.
import { BUCKETS, type BackfillRequest, type CurrencyFlows, type CurrencyGap, type Irrbb } from './contracts'

/** Curve indices risk-engine accepts (CurveIndex.kt). An unknown one is a 400 there. */
export const CURVE_INDICES = ['CZEONIA', 'PRIBOR_1M', 'PRIBOR_3M', 'PRIBOR_6M', 'ESTR', 'EURIBOR_3M'] as const
export type CurveIndexName = typeof CURVE_INDICES[number]

// ── Maturity ladder ───────────────────────────────────────────────────────────────────────────

export type LadderRow = { bucket: string; inflow: number; outflow: number; net: number }

/**
 * One row per risk-engine bucket, in the engine's order. The engine reports ONE bank-signed net
 * amount per bucket (inflow positive, outflow negative), so a bucket is either an inflow bar or an
 * outflow bar — never both. A bucket the engine did not report is 0 because the engine lists every
 * bucket it filled; the page states unpriced currencies and unexpanded positions separately, so a
 * missing VALUE is never rendered as a zero bar.
 */
export function ladderRows(flows: CurrencyFlows): LadderRow[] {
  const byBucket = new Map(flows.buckets.map(b => [b.bucket, b.amount]))
  const extra = flows.buckets.map(b => b.bucket).filter(b => !(BUCKETS as readonly string[]).includes(b))
  return [...BUCKETS, ...extra].map(bucket => {
    const net = byBucket.get(bucket) ?? 0
    return { bucket, inflow: net > 0 ? net : 0, outflow: net < 0 ? net : 0, net }
  })
}

/** Cumulative net gap per bucket — the liquidity-gap line an ALM desk reads off a ladder. */
export function cumulativeGap(rows: LadderRow[]): number[] {
  let running = 0
  return rows.map(r => (running += r.net))
}

// ── Identity: who the backend will record as maker / checker ──────────────────────────────────

/**
 * The name Quarkus OIDC takes as the principal of a bearer token (default `principal-claim`
 * resolution: `upn`, then `preferred_username`, then `sub`). LedgerBackfillService records THIS as
 * `proposedBy`/`decidedBy`, so comparing against it — not the display name — is what makes the
 * UI's "you proposed this" check agree with the server's four-eyes check. Returns null for an
 * absent or unreadable token; the page then relies on the backend 422 alone.
 */
export function principalNameFromToken(token: string | null | undefined): string | null {
  if (!token) return null
  const part = token.split('.')[1]
  if (!part) return null
  try {
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '=')
    const claims = JSON.parse(atob(b64)) as Record<string, unknown>
    for (const key of ['upn', 'preferred_username', 'sub']) {
      const v = claims[key]
      if (typeof v === 'string' && v.length > 0) return v
    }
    return null
  } catch {
    return null
  }
}

export type BackfillActions = {
  canDecide: boolean
  canExecute: boolean
  /** Why decide is not offered, when it is not: the four-eyes rule, or the request's state. */
  decideBlockedBy: 'own-proposal' | 'state' | 'permission' | null
}

/**
 * What the current user may do with one request. The UI hides approve for the proposer, but the
 * server is the control: LedgerBackfillService answers 422 on a self-approval whatever the UI
 * shows, and the page renders that refusal. A null [actor] (token unreadable) never hides approve.
 */
export function backfillActions(
  request: BackfillRequest,
  actor: string | null,
  perms: { decide: boolean; execute: boolean },
): BackfillActions {
  const own = actor !== null && request.proposedBy === actor
  let decideBlockedBy: BackfillActions['decideBlockedBy'] = null
  if (!perms.decide) decideBlockedBy = 'permission'
  else if (request.state !== 'PROPOSED') decideBlockedBy = 'state'
  else if (own) decideBlockedBy = 'own-proposal'
  return {
    canDecide: decideBlockedBy === null,
    canExecute: perms.execute && request.state === 'APPROVED',
    decideBlockedBy,
  }
}

// ── Curve-set upload form ─────────────────────────────────────────────────────────────────────

const TENOR = /^(ON|[1-9][0-9]{0,2}[DWMY])$/

export type QuoteParse =
  | { ok: true; curves: Record<string, { tenor: string; rate: number }[]>; count: number }
  | { ok: false; errors: { line: number; code: 'format' | 'index' | 'tenor' | 'rate' | 'duplicate' | 'empty' }[] }

/**
 * Parses the upload textarea: one quote per line, `INDEX TENOR RATE%` (whitespace, `;` or `,`
 * separated; a decimal comma inside the rate is accepted). Rates are typed in PERCENT, as a desk
 * reads them, and sent as the decimal simple rate risk-engine expects (3.5 → 0.035). Every line is
 * validated before anything is sent — the service would 400 on the same input, but the operator
 * should see which line, not a generic failure.
 */
export function parseQuotes(text: string): QuoteParse {
  const errors: { line: number; code: 'format' | 'index' | 'tenor' | 'rate' | 'duplicate' | 'empty' }[] = []
  const curves: Record<string, { tenor: string; rate: number }[]> = {}
  let count = 0
  text.split('\n').forEach((raw, i) => {
    const line = raw.trim()
    if (!line || line.startsWith('#')) return
    const cols = line.split(/[\s;]+/).filter(Boolean)
    if (cols.length !== 3) { errors.push({ line: i + 1, code: 'format' }); return }
    const [index, tenorRaw, rateRaw] = cols
    const tenor = tenorRaw.toUpperCase()
    const rate = Number(rateRaw.replace('%', '').replace(',', '.'))
    if (!(CURVE_INDICES as readonly string[]).includes(index.toUpperCase())) { errors.push({ line: i + 1, code: 'index' }); return }
    if (!TENOR.test(tenor)) { errors.push({ line: i + 1, code: 'tenor' }); return }
    if (!Number.isFinite(rate) || rate < -5 || rate > 50) { errors.push({ line: i + 1, code: 'rate' }); return }
    const key = index.toUpperCase()
    const list = (curves[key] ??= [])
    if (list.some(q => q.tenor === tenor)) { errors.push({ line: i + 1, code: 'duplicate' }); return }
    list.push({ tenor, rate: Math.round(rate * 1e6) / 1e8 })
    count++
  })
  if (errors.length) return { ok: false, errors }
  if (count === 0) return { ok: false, errors: [{ line: 0, code: 'empty' }] }
  return { ok: true, curves, count }
}

/** An ISO date (YYYY-MM-DD) that is a real calendar day. */
export function isIsoDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const d = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === value
}

/** Today in Europe/Prague as YYYY-MM-DD — the bank's accounting day, which the backfill cut-over must not precede. */
export function bankToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Prague', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}

// ── IRRBB ─────────────────────────────────────────────────────────────────────────────────────

export type GapRow = { bucket: string; assets: number; liabilities: number; gap: number; cumulativeGap: number }

/** Repricing-gap rows in the engine's order; liabilities drawn DOWN (negated) so the bars read as a ladder. */
export function gapRows(gap: CurrencyGap): GapRow[] {
  return gap.buckets.map(b => ({ bucket: b.bucket, assets: b.assets, liabilities: -b.liabilities, gap: b.gap, cumulativeGap: b.cumulativeGap }))
}

/**
 * A caller-typed Tier 1 figure, or null. Only a positive decimal is accepted; anything else means
 * "not supplied" — the page never substitutes a figure, so the outlier ratio stays unshown.
 */
export function parseTier1(raw: string): string | null {
  const v = raw.trim().replace(/\s/g, '').replace(',', '.')
  if (!/^[0-9]+(\.[0-9]+)?$/.test(v)) return null
  return Number(v) > 0 ? v : null
}

/** The outlier ratio to SHOW: only when Tier 1 was supplied and the engine computed a ratio. */
export function outlierRatio(irrbb: Irrbb): number | null {
  const o = irrbb.outlierTest
  return o.tier1Supplied && typeof o.ratio === 'number' ? o.ratio : null
}
