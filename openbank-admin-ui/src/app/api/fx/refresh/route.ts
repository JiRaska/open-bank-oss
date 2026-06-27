// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

export async function POST(req: NextRequest) {
  const body = await req.json().catch(() => ({}))
  const source: 'cnb' | 'ecb' | 'all' = body?.source ?? 'all'

  const results: Record<string, { ok: boolean; count?: number; error?: string; syncedAt: string }> = {}
  const now = new Date().toISOString()

  if (source === 'cnb' || source === 'all') {
    try {
      const res = await fetch('https://api.cnb.cz/cnbapi/exrates/daily?lang=EN', {
        signal: AbortSignal.timeout(10000),
        headers: { 'Accept': 'application/json' },
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      results.cnb = { ok: true, count: data?.rates?.length ?? 0, syncedAt: now }
    } catch (e: any) {
      results.cnb = { ok: false, error: e?.message ?? 'Unknown error', syncedAt: now }
    }
  }

  if (source === 'ecb' || source === 'all') {
    try {
      const url =
        'https://data-api.ecb.europa.eu/service/data/EXR/D.USD+GBP+JPY+CHF+PLN+HUF+RON+SEK+NOK+DKK+AUD+CAD+CNY.EUR.SP00.A?lastNObservations=1&format=jsondata'
      const res = await fetch(url, {
        signal: AbortSignal.timeout(12000),
        headers: { 'Accept': 'application/json' },
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      const count = Object.keys(data?.dataSets?.[0]?.series ?? {}).length
      results.ecb = { ok: true, count, syncedAt: now }
    } catch (e: any) {
      results.ecb = { ok: false, error: e?.message ?? 'Unknown error', syncedAt: now }
    }
  }

  return NextResponse.json({ results, refreshedAt: now })
}
