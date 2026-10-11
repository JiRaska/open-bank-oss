// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Pyrra writes the governed SLO's 30-day increase recording rules into Prometheus.
// Reading those rules through Admin UI's existing Prometheus transport returns the
// same availability and error-budget evidence as Pyrra without adding another
// plaintext service edge or trying to bypass Pyrra's user-facing identity gate.

import { NextResponse } from 'next/server'
import { readFileSync } from 'node:fs'
import path from 'node:path'

export const dynamic = 'force-dynamic'

const TARGET = 0.999
// The generated catalog carries rules.yaml:money_path_services for every runnable module.
// Pyrra creates one availability SLO per member; a six-name UI list hid 22 of 28 declared
// objectives while still calling itself complete. The snapshot is baked into the image.
function moneyPathSloNames(): string[] {
  const file = process.env.OPENBANK_CATALOG ?? path.resolve(process.cwd(), 'catalog.json')
  const catalog = JSON.parse(readFileSync(file, 'utf8')) as { services?: { name?: unknown; moneyPath?: unknown }[] }
  if (!Array.isArray(catalog.services)) throw new Error('service catalog unavailable')
  const names = catalog.services
    .filter(service => service.moneyPath === true)
    .map(service => {
      if (typeof service.name !== 'string' || !/^openbank-[a-z0-9]+(?:-[a-z0-9]+)*$/.test(service.name)) {
        throw new Error('invalid money-path service name in catalog')
      }
      return `${service.name.replace(/-service$/, '')}-availability`
    })
  if (!names.length || new Set(names).size !== names.length) throw new Error('invalid money-path SLO set in catalog')
  return names.sort()
}

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
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 8000)
  let configured = 0

  try {
    const objectives = moneyPathSloNames()
    configured = objectives.length
    const selector = objectives.join('|')
    const allowed = new Set(objectives)
    const [totals, errors] = await Promise.all([
      queryVector(`sum by (slo) (traces_spanmetrics_calls:increase30d{slo=~"${selector}"})`, controller.signal, allowed),
      queryVector(`sum by (slo) (traces_spanmetrics_calls:increase30d{slo=~"${selector}",status_code="STATUS_CODE_ERROR"})`, controller.signal, allowed),
    ])

    const summaries = objectives.map(name => {
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
      configured: objectives.length,
      monitored: summaries.filter(objective => objective.requestCount !== null).length,
      objectives: summaries,
    }, { headers: { 'Cache-Control': 'no-store' } })
  } catch (error) {
    return NextResponse.json({
      available: false,
      error: configured === 0 ? 'catalog_unavailable'
        : error instanceof DOMException && error.name === 'AbortError' ? 'prometheus_timeout' : 'pyrra_evidence_unreachable',
      configured,
      monitored: 0,
      objectives: [],
    }, { status: 502, headers: { 'Cache-Control': 'no-store' } })
  } finally {
    clearTimeout(timer)
  }
}
