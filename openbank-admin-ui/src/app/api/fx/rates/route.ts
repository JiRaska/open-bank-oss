// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { CURRENCY_META } from '@/lib/currency-meta'
import { inCluster, discoverServices, resolveInClusterBaseUrl } from '@/lib/discovery'

// Off-cluster (local dev / docker-compose) fallback only. In-cluster we resolve
// fx-service through discovery, never this literal — the real Service is
// `fx-service.fx.svc:8119`, not `openbank-fx-service`.
const FX_SERVICE_URL = process.env.FX_SERVICE_URL ?? 'http://localhost:8119'

export const dynamic = 'force-dynamic'

// fx-service is on the FinOps off-hours scaledown allowlist (fx/rollout/fx-service),
// so it legitimately sits at zero replicas overnight/weekends. Resolve its state via
// discovery so the FX page can show a calm "idle (scale-to-zero)" badge instead of a
// misleading red "down" — and so a genuinely-up service isn't mislabelled red just
// because the old hardcoded `openbank-fx-service` host never resolved.
type FxStatus = 'up' | 'scaled_to_zero' | 'down' | 'not_deployed'

async function resolveFxService(): Promise<{ status: FxStatus; baseUrl: string | null }> {
  if (!inCluster()) {
    // Local dev: probe the direct URL; can't distinguish scale-to-zero here.
    try {
      const res = await fetch(`${FX_SERVICE_URL}/q/health/ready`, { signal: AbortSignal.timeout(3000) })
      return { status: res.ok ? 'up' : 'down', baseUrl: FX_SERVICE_URL }
    } catch {
      return { status: 'down', baseUrl: FX_SERVICE_URL }
    }
  }
  const discovered = await discoverServices()
  const fx = discovered?.find((d) => d.name === 'fx-service')
  if (!fx) return { status: 'not_deployed', baseUrl: null }
  if (fx.scaledToZero) return { status: 'scaled_to_zero', baseUrl: null }
  // readyReplicas IS the kubelet's /q/health/ready verdict re-published by the control
  // plane, so we trust discovery's readiness rather than a second in-band health probe.
  const baseUrl = await resolveInClusterBaseUrl('fx-service')
  return { status: fx.ready ? 'up' : 'down', baseUrl }
}

async function fetchCnbRates() {
  const res = await fetch('https://api.cnb.cz/cnbapi/exrates/daily?lang=EN', {
    signal: AbortSignal.timeout(8000),
    headers: { Accept: 'application/json' },
  })
  if (!res.ok) throw new Error(`CNB HTTP ${res.status}`)
  const data = await res.json()
  return (data?.rates ?? []) as Array<{
    currencyCode: string
    amount: number
    rate: number
    validFor: string
    country: string
    currency: string
  }>
}

async function fetchEcbRates() {
  const url =
    'https://data-api.ecb.europa.eu/service/data/EXR/D.USD+GBP+JPY+CHF+PLN+HUF+RON+SEK+NOK+DKK+AUD+CAD+CNY.EUR.SP00.A?lastNObservations=1&format=jsondata'
  const res = await fetch(url, {
    signal: AbortSignal.timeout(10000),
    headers: { Accept: 'application/json' },
  })
  if (!res.ok) throw new Error(`ECB HTTP ${res.status}`)
  const data = await res.json()

  const dimensions: any[] = data?.structure?.dimensions?.series ?? []
  const currencyDimIndex = dimensions.findIndex((d: any) => d.id === 'CURRENCY')
  const dimPosition = currencyDimIndex >= 0 ? currencyDimIndex : 1
  const currencies: string[] = dimensions[dimPosition]?.values?.map((v: any) => v.id) ?? []

  const timePeriods: any[] = data?.structure?.dimensions?.observation?.[0]?.values ?? []
  const series: Record<string, any> = data?.dataSets?.[0]?.series ?? {}

  const result: Array<{ currency: string; rate: number; date: string }> = []
  Object.entries(series).forEach(([key, val]) => {
    const parts = key.split(':')
    const idx = parseInt(parts[dimPosition] ?? parts[0], 10)
    const currency = currencies[idx]
    const obs: Record<string, any[]> = val?.observations ?? {}
    const obsKeys = Object.keys(obs)
    if (obsKeys.length === 0 || !currency) return
    const lastKey = obsKeys[obsKeys.length - 1]
    const rate = parseFloat(obs[lastKey]?.[0])
    const date = timePeriods[parseInt(lastKey, 10)]?.id ?? ''
    if (currency && !isNaN(rate)) result.push({ currency, rate, date })
  })
  return result
}

/** Outcome of one fx-service read.
 *
 *  Deliberately NOT a bare array. `GET /api/v1/fx/rates` is
 *  `@RolesAllowed(ROLE_VIEWER, ROLE_OPERATOR, ROLE_ADMIN, ROLE_PAYMENTS)`, and this route used to
 *  call it with no `Authorization` header at all — so 401 was the expected response, and
 *  `if (!res.ok) return []` rendered it as "no rates". A missing credential, a 403, a timeout and a
 *  genuinely empty rate table were four states reduced to one value, with nothing downstream able
 *  to tell them apart. `cnb` and `ecb` in this same response already carry an `error` field;
 *  fxService did not. */
type FxFetch = { rows: unknown[]; error: string | null }

const EMPTY: FxFetch = { rows: [], error: null }

async function fetchFxServiceRates(baseUrl: string, accessToken: string | undefined): Promise<FxFetch> {
  if (!accessToken) return { rows: [], error: 'unauthenticated: no operator bearer to relay' }
  try {
    const res = await fetch(`${baseUrl}/api/v1/fx/rates`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      signal: AbortSignal.timeout(5000),
    })
    if (!res.ok) return { rows: [], error: `fx-service responded ${res.status}` }
    const data = await res.json()
    return { rows: Array.isArray(data) ? data : (data?.rates ?? []), error: null }
  } catch (e) {
    return { rows: [], error: String(e) }
  }
}

async function fetchFxConversions(baseUrl: string, accessToken: string | undefined): Promise<FxFetch> {
  if (!accessToken) return { rows: [], error: 'unauthenticated: no operator bearer to relay' }
  try {
    const res = await fetch(`${baseUrl}/api/v1/fx/conversions`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      signal: AbortSignal.timeout(5000),
    })
    if (!res.ok) return { rows: [], error: `fx-service responded ${res.status}` }
    const data = await res.json()
    return { rows: Array.isArray(data) ? data : (data?.conversions ?? []), error: null }
  } catch (e) {
    return { rows: [], error: String(e) }
  }
}

export async function GET() {
  const fx = await resolveFxService()
  // Relay the operator's own bearer, so fx-service's policy stays authoritative — the same
  // pattern the agent BFF routes use. Without it every call here was a 401 rendered as "no rates".
  const accessToken = (await auth())?.user?.accessToken
  // Only reach out to fx-service when it's actually serving; a scaled-to-zero or
  // undeployed service has no reachable pod, so skip the fetch (and its timeout).
  const canFetch = fx.status === 'up' && fx.baseUrl !== null
  const [cnbRates, ecbRates, fxRates, conversions] = await Promise.allSettled([
    fetchCnbRates(),
    fetchEcbRates(),
    canFetch ? fetchFxServiceRates(fx.baseUrl!, accessToken) : Promise.resolve(EMPTY),
    canFetch ? fetchFxConversions(fx.baseUrl!, accessToken) : Promise.resolve(EMPTY),
  ])

  return NextResponse.json({
    cnb: {
      rates: cnbRates.status === 'fulfilled' ? cnbRates.value : [],
      syncedAt: cnbRates.status === 'fulfilled' && cnbRates.value.length > 0 ? new Date().toISOString() : null,
      error: cnbRates.status === 'rejected' ? String(cnbRates.reason) : null,
    },
    ecb: {
      rates: ecbRates.status === 'fulfilled' ? ecbRates.value : [],
      syncedAt: ecbRates.status === 'fulfilled' && ecbRates.value.length > 0 ? new Date().toISOString() : null,
      error: ecbRates.status === 'rejected' ? String(ecbRates.reason) : null,
    },
    fxService: {
      status: fx.status,
      // `up` kept for backward-compat with any older client; `status` is authoritative.
      up: fx.status === 'up',
      rates: fxRates.status === 'fulfilled' ? fxRates.value.rows : [],
      conversions: conversions.status === 'fulfilled' ? conversions.value.rows : [],
      // Present so an unauthenticated or failing read is distinguishable from an empty one,
      // matching the `error` field cnb and ecb already carry above.
      error: fxRates.status === 'fulfilled'
        ? fxRates.value.error
        : String((fxRates as PromiseRejectedResult).reason),
      conversionsError: conversions.status === 'fulfilled'
        ? conversions.value.error
        : String((conversions as PromiseRejectedResult).reason),
    },
    currencyMeta: CURRENCY_META,
    fetchedAt: new Date().toISOString(),
  })
}
