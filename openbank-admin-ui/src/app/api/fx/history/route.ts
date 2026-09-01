// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'
import { inCluster, discoverServices, resolveInClusterBaseUrl } from '@/lib/discovery'
import { auth } from '@/auth'
import { buildCnbTrend, defaultTrendWindow, type FxTrend } from '@/lib/fx/trend'

// Off-cluster (local dev / docker-compose) fallback only — see src/app/api/fx/rates/route.ts.
const FX_SERVICE_URL = process.env.FX_SERVICE_URL ?? 'http://localhost:8119'

export const dynamic = 'force-dynamic'

async function resolveFxServiceBaseUrl(): Promise<string | null> {
  if (!inCluster()) {
    try {
      const res = await fetch(`${FX_SERVICE_URL}/q/health/ready`, { signal: AbortSignal.timeout(3000) })
      return res.ok ? FX_SERVICE_URL : null
    } catch {
      return null
    }
  }
  const discovered = await discoverServices()
  const fx = discovered?.find((d) => d.name === 'fx-service')
  if (!fx || fx.scaledToZero || !fx.ready) return null
  return resolveInClusterBaseUrl('fx-service')
}

/**
 * Fetch one pair's CNB history, relaying the operator's own bearer.
 *
 * fx-service guards this route with
 * `@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")`, so calling it with
 * no `Authorization` header answers 401 and this whole endpoint would return `upstream_error` for
 * every request in a deployed cluster — the trend would never render at all. Same relay the fx rates
 * route uses; fx-service's policy stays authoritative. The caller guarantees a token (GET answers
 * 401 without one), so a failure reaching here is genuinely an upstream failure.
 */
async function fetchHistory(
  baseUrl: string,
  base: string,
  quote: string,
  from: string,
  to: string,
  accessToken: string,
) {
  const url =
    `${baseUrl}/api/v1/fx/rates/${base}/${quote}/history?source=CNB` +
    `&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&limit=100`
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${accessToken}` },
    signal: AbortSignal.timeout(8000),
  })
  if (!res.ok) return null
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

/**
 * The three-calendar-month ČNB reference-mid trend for a pair (issue #7735) — the SAME truthful,
 * chronological, deduped, inverse-pair-aware data the customer app renders via customer-edge's
 * `GET /customer/v1/fx/rates/{base}/{quote}/history` (see `mapFxHistoryList` there and
 * `buildCnbTrend`/`defaultTrendWindow` in `src/lib/fx/trend.ts`, which mirror it deliberately so
 * the two surfaces cannot drift). Replaces the admin portal's former client-memory rate
 * "snapshots" (captured only on manual refresh, never persisted, never a real time series).
 */
export async function GET(req: NextRequest) {
  const { searchParams } = new URL(req.url)
  const base = (searchParams.get('base') ?? 'EUR').toUpperCase()
  const quote = (searchParams.get('quote') ?? 'CZK').toUpperCase()
  if (!/^[A-Z]{3}$/.test(base) || !/^[A-Z]{3}$/.test(quote)) {
    return NextResponse.json({ error: 'invalid_currency' }, { status: 400 })
  }

  const baseUrl = await resolveFxServiceBaseUrl()
  if (!baseUrl) {
    return NextResponse.json({ indicative: true, base, quote, points: [], unavailable: true } satisfies FxTrend & {
      unavailable: boolean
    })
  }

  // Relay the operator's own bearer — fx-service's history route is role-guarded. Answered as 401
  // rather than folded into the 502 below: "you are not authenticated" and "the upstream failed" are
  // different problems with different fixes, and collapsing them makes the first one unreportable.
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) {
    return NextResponse.json({ error: 'unauthenticated: no operator bearer to relay' }, { status: 401 })
  }

  const { from, to } = defaultTrendWindow()
  const direct = await fetchHistory(baseUrl, base, quote, from, to, accessToken)
  if (direct === null) {
    return NextResponse.json({ error: 'upstream_error' }, { status: 502 })
  }

  let trend = buildCnbTrend(direct, base, quote, false)
  if (trend.points.length === 0 && base !== quote) {
    const inverse = await fetchHistory(baseUrl, quote, base, from, to, accessToken)
    if (inverse !== null) {
      trend = buildCnbTrend(inverse, base, quote, true)
    }
  }
  return NextResponse.json(trend)
}
