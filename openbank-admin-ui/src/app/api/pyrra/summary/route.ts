// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Pyrra writes the governed SLO's 30-day increase recording rules into Prometheus.
// Reading those rules through Admin UI's existing Prometheus transport returns the
// same availability and error-budget evidence as Pyrra without adding another
// plaintext service edge or trying to bypass Pyrra's user-facing identity gate.

import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

const TARGET = 0.999
const CUSTOMER_JOURNEY_SLOS = [
  'openbank-transaction-availability',
  'openbank-ledger-availability',
  'openbank-sepa-instant-availability',
  'openbank-domestic-payment-availability',
  'openbank-settlement-availability',
  'openbank-fraud-availability',
] as const

type Sample = { metric: Record<string, string>; value: [number, string] }

function prometheusBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return process.env.PROMETHEUS_URL ?? 'http://localhost:9090'
}

async function queryVector(query: string, signal: AbortSignal): Promise<Map<string, number>> {
  const response = await fetch(`${prometheusBase()}/api/v1/query?query=${encodeURIComponent(query)}`, {
    signal,
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) throw new Error(`prometheus responded ${response.status}`)
  const payload = await response.json() as { status?: unknown; data?: { result?: unknown } }
  if (payload.status !== 'success' || !Array.isArray(payload.data?.result)) throw new Error('invalid prometheus response')

  const rows = new Map<string, number>()
  for (const raw of payload.data.result as Sample[]) {
    const slo = raw?.metric?.slo
    const value = Array.isArray(raw?.value) ? Number(raw.value[1]) : Number.NaN
    if (typeof slo !== 'string' || !CUSTOMER_JOURNEY_SLOS.includes(slo as typeof CUSTOMER_JOURNEY_SLOS[number]) || !Number.isFinite(value) || value < 0) {
      throw new Error('invalid prometheus sample')
    }
    rows.set(slo, value)
  }
  return rows
}

export async function GET() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 8000)
  const selector = 'openbank-(transaction|ledger|sepa-instant|domestic-payment|settlement|fraud)-availability'

  try {
    const [totals, errors] = await Promise.all([
      queryVector(`sum by (slo) (traces_spanmetrics_calls:increase30d{slo=~"${selector}"})`, controller.signal),
      queryVector(`sum by (slo) (traces_spanmetrics_calls:increase30d{slo=~"${selector}",status_code="STATUS_CODE_ERROR"})`, controller.signal),
    ])

    const objectives = CUSTOMER_JOURNEY_SLOS.map(name => {
      const total = totals.get(name)
      if (total === undefined || total === 0) {
        return { name, target: TARGET, window: '30d', budgetRemaining: null, availability: null, requestCount: total ?? null }
      }
      const errorCount = errors.get(name) ?? 0
      const errorRatio = errorCount / total
      return {
        name,
        target: TARGET,
        window: '30d',
        budgetRemaining: 1 - errorRatio / (1 - TARGET),
        availability: 1 - errorRatio,
        requestCount: total,
      }
    })

    return NextResponse.json({
      available: true,
      configured: CUSTOMER_JOURNEY_SLOS.length,
      monitored: objectives.filter(objective => objective.requestCount !== null).length,
      objectives,
    }, { headers: { 'Cache-Control': 'no-store' } })
  } catch (error) {
    return NextResponse.json({
      available: false,
      error: error instanceof DOMException && error.name === 'AbortError' ? 'prometheus_timeout' : 'pyrra_evidence_unreachable',
      configured: CUSTOMER_JOURNEY_SLOS.length,
      monitored: 0,
      objectives: [],
    }, { status: 502, headers: { 'Cache-Control': 'no-store' } })
  } finally {
    clearTimeout(timer)
  }
}
