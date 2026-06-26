// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'
import { CURRENCY_META } from '@/lib/currency-meta'

const FX_SERVICE_URL = process.env.FX_SERVICE_URL ?? 'http://openbank-fx-service:8119'

export const dynamic = 'force-dynamic'

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

async function fetchFxServiceHealth(): Promise<boolean> {
  try {
    const res = await fetch(`${FX_SERVICE_URL}/q/health/ready`, { signal: AbortSignal.timeout(3000) })
    return res.ok
  } catch {
    return false
  }
}

async function fetchFxServiceRates() {
  try {
    const res = await fetch(`${FX_SERVICE_URL}/api/v1/fx/rates`, { signal: AbortSignal.timeout(5000) })
    if (!res.ok) return []
    const data = await res.json()
    return Array.isArray(data) ? data : (data?.rates ?? [])
  } catch {
    return []
  }
}

async function fetchFxConversions() {
  try {
    const res = await fetch(`${FX_SERVICE_URL}/api/v1/fx/conversions`, { signal: AbortSignal.timeout(5000) })
    if (!res.ok) return []
    const data = await res.json()
    return Array.isArray(data) ? data : (data?.conversions ?? [])
  } catch {
    return []
  }
}

export async function GET() {
  const [cnbRates, ecbRates, serviceUp, fxRates, conversions] = await Promise.allSettled([
    fetchCnbRates(),
    fetchEcbRates(),
    fetchFxServiceHealth(),
    fetchFxServiceRates(),
    fetchFxConversions(),
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
      up: serviceUp.status === 'fulfilled' ? serviceUp.value : false,
      rates: fxRates.status === 'fulfilled' ? fxRates.value : [],
      conversions: conversions.status === 'fulfilled' ? conversions.value : [],
    },
    currencyMeta: CURRENCY_META,
    fetchedAt: new Date().toISOString(),
  })
}
