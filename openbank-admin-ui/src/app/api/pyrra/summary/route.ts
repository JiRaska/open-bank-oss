// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Pyrra writes the governed SLO's 30-day increase recording rules into Prometheus.
// Reading those rules through Admin UI's existing Prometheus transport returns the
// same availability and error-budget evidence as Pyrra without adding another
// plaintext service edge or trying to bypass Pyrra's user-facing identity gate.

import { NextResponse } from 'next/server'
import { moneyPathAvailabilitySlo } from '@/lib/governance/moneyPath'

export const dynamic = 'force-dynamic'

type Sample = { metric: Record<string, string>; value: [number, string] }

function prometheusBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return process.env.PROMETHEUS_URL ?? 'http://localhost:9090'
}

async function queryVector(query: string, signal: AbortSignal, allowed: ReadonlySet<string>): Promise<Map<string, number>> {
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
    if (typeof slo !== 'string' || !allowed.has(slo) || !Number.isFinite(value) || value < 0) {
      throw new Error('invalid prometheus sample')
    }
    rows.set(slo, value)
  }
  return rows
}

export async function GET() {
  let declared: ReturnType<typeof moneyPathAvailabilitySlo>
  try {
    declared = moneyPathAvailabilitySlo()
  } catch {
    return NextResponse.json({ available: false, error: 'slo_catalog_unavailable', configured: 0, monitored: 0, objectives: [] },
      { status: 503, headers: { 'Cache-Control': 'no-store' } })
  }
  const allowed = new Set(declared.names)
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 8000)
  const selector = declared.names.join('|')
  const recordingRule = `traces_spanmetrics_calls:increase${declared.window}`

  try {
    const [totals, errors] = await Promise.all([
      queryVector(`sum by (slo) (${recordingRule}{slo=~"${selector}"})`, controller.signal, allowed),
      queryVector(`sum by (slo) (${recordingRule}{slo=~"${selector}",status_code="STATUS_CODE_ERROR"})`, controller.signal, allowed),
    ])

    const objectives = declared.names.map(name => {
      const total = totals.get(name)
      if (total === undefined || total === 0) {
        return { name, target: declared.target, window: declared.window, budgetRemaining: null, availability: null, requestCount: total ?? null }
      }
      const errorCount = errors.get(name) ?? 0
      const errorRatio = errorCount / total
      return {
        name,
        target: declared.target,
        window: declared.window,
        budgetRemaining: 1 - errorRatio / (1 - declared.target),
        availability: 1 - errorRatio,
        requestCount: total,
      }
    })

    return NextResponse.json({
      available: true,
      configured: declared.names.length,
      monitored: objectives.filter(objective => objective.requestCount !== null && objective.requestCount > 0).length,
      objectives,
    }, { headers: { 'Cache-Control': 'no-store' } })
  } catch (error) {
    return NextResponse.json({
      available: false,
      error: error instanceof DOMException && error.name === 'AbortError' ? 'prometheus_timeout' : 'pyrra_evidence_unreachable',
      configured: declared.names.length,
      monitored: 0,
      objectives: [],
    }, { status: 502, headers: { 'Cache-Control': 'no-store' } })
  } finally {
    clearTimeout(timer)
  }
}
