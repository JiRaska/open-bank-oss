// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

function prometheusBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return process.env.PROMETHEUS_URL ?? 'http://localhost:9090'
}

async function queryRange(query: string, signal: AbortSignal): Promise<Record<string, number>> {
  try {
    const url = `${prometheusBase()}/api/v1/query?query=${encodeURIComponent(query)}`
    const res = await fetch(url, { signal, headers: { Accept: 'application/json' } })
    if (!res.ok) return {}
    const json = await res.json() as { status: string; data: { result: { metric: Record<string, string>; value: [number, string] }[] } }
    if (json.status !== 'success') return {}
    const out: Record<string, number> = {}
    for (const r of json.data.result) {
      const svc = r.metric.container ?? r.metric.application ?? r.metric.job ?? r.metric.service ?? 'unknown'
      const val = parseFloat(r.value[1])
      if (!isNaN(val)) out[svc] = val
    }
    return out
  } catch {
    return {}
  }
}

// Normalise container labels ("account-service") → short service key
function short(name: string): string {
  return name.replace(/^openbank-/, '').replace(/-service$/, '')
}

export async function GET() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 8000)

  try {
    // Check Prometheus liveness first
    const ready = await fetch(`${prometheusBase()}/-/ready`, {
      signal: controller.signal,
    }).catch(() => null)

    if (!ready?.ok) {
      return NextResponse.json({ available: false, services: [] }, { headers: { 'Cache-Control': 'no-store' } })
    }

    const [heapUsed, heapMax, cpuRate, reqRate] = await Promise.all([
      // Prometheus scrapes Quarkus pods via ServiceMonitor job "observability/openbank-services".
      // The per-pod identity label is `container`, not `application` — Quarkus Micrometer does
      // not set a common application tag by default. Filter by job to exclude kafka/keycloak JVM.
      queryRange('sum(jvm_memory_used_bytes{area="heap",job="observability/openbank-services"}) by (container)', controller.signal),
      queryRange('sum(jvm_memory_max_bytes{area="heap",job="observability/openbank-services"}) by (container)', controller.signal),
      queryRange('sum(rate(process_cpu_seconds_total{job="observability/openbank-services"}[5m])) by (container)', controller.signal),
      queryRange('sum(rate(http_server_requests_seconds_count{job="observability/openbank-services"}[5m])) by (container)', controller.signal),
    ])

    const allApps = new Set([
      ...Object.keys(heapUsed),
      ...Object.keys(heapMax),
      ...Object.keys(cpuRate),
      ...Object.keys(reqRate),
    ])

    const services = Array.from(allApps)
      .map(app => {
        const used = heapUsed[app] ?? 0
        const max = heapMax[app] ?? 0
        const heapPct = max > 0 ? Math.round((used / max) * 100) : null
        const cpu = cpuRate[app] ?? null
        const rps = reqRate[app] ?? null

        return {
          name: app,
          short: short(app),
          heap: {
            usedBytes: Math.round(used),
            maxBytes: Math.round(max),
            pct: heapPct,
          },
          cpuCoresUsed: cpu !== null ? Math.round(cpu * 1000) / 1000 : null,
          requestsPerSec: rps !== null ? Math.round(rps * 100) / 100 : null,
          efficiency: heapPct !== null
            ? heapPct < 50 ? 'underutilised'
              : heapPct < 80 ? 'normal'
              : 'high'
            : 'unknown',
        }
      })
      .sort((a, b) => (b.heap.pct ?? 0) - (a.heap.pct ?? 0))

    const avgHeap = services.filter(s => s.heap.pct !== null).reduce((sum, s) => sum + (s.heap.pct ?? 0), 0)
    const withHeap = services.filter(s => s.heap.pct !== null).length
    const fleetHeapPct = withHeap > 0 ? Math.round(avgHeap / withHeap) : null
    const underutilised = services.filter(s => s.efficiency === 'underutilised').length

    return NextResponse.json({
      available: true,
      fleetHeapPct,
      underutilisedCount: underutilised,
      serviceCount: services.length,
      services,
      collectedAt: new Date().toISOString(),
    }, { headers: { 'Cache-Control': 'no-store' } })
  } finally {
    clearTimeout(timer)
  }
}
