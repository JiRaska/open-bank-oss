// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

// Resource efficiency data per service namespace. VPA (ADR-0099) runs in
// updateMode: Off — it fills status.recommendation but never touches pods.
// The recommender exposes Prometheus metrics; until those are scraped we
// compute efficiency from kube-state-metrics (requests) vs cAdvisor (actuals).

interface ServiceEfficiency {
  namespace: string
  displayName: string
  cpu: {
    requestedMillicores: number | null
    usedMillicores: number | null
    efficiencyPct: number | null     // used/requested × 100; null = data gap
  }
  memory: {
    requestedMiB: number | null
    usedMiB: number | null
    efficiencyPct: number | null
  }
  savingsPotential: 'high' | 'medium' | 'low' | 'unknown'
  vpaRecommendation: {
    cpuMillicores: number | null
    memoryMiB: number | null
  }
}

interface RightSizingReport {
  available: boolean
  collectedAt: string
  fleetCpuEfficiencyPct: number | null
  fleetMemEfficiencyPct: number | null
  highSavingsCount: number
  services: ServiceEfficiency[]
  vpaHasData: boolean
  /** Null when every query answered. Non-null names the queries that did not, so an empty
   *  `services` under a failing read is not read as "the fleet has no namespaces" (#7943). */
  error: string | null
  degraded: boolean
}

function prometheusBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return process.env.PROMETHEUS_URL ?? 'http://localhost:9090'
}

type Vector = { rows: { metric: Record<string, string>; value: number }[]; error: string | null }

/** Deliberately NOT a bare array — see the resources route for the same reasoning. The `/-/ready`
 *  probe already distinguishes an unreachable Prometheus; a ready Prometheus whose QUERIES fail
 *  used to render as `available: true, services: []`, i.e. a perfectly efficient fleet. */
async function queryVector(
  query: string,
  signal: AbortSignal,
): Promise<Vector> {
  try {
    const url = `${prometheusBase()}/api/v1/query?query=${encodeURIComponent(query)}`
    const res = await fetch(url, { signal, headers: { Accept: 'application/json' } })
    if (!res.ok) return { rows: [], error: `prometheus responded ${res.status}` }
    const json = await res.json() as {
      status: string
      data: { result: { metric: Record<string, string>; value: [number, string] }[] }
    }
    if (json.status !== 'success') return { rows: [], error: `prometheus query status ${json.status}` }
    return {
      rows: json.data.result.map(r => ({
        metric: r.metric,
        value: parseFloat(r.value[1]),
      })).filter(r => !isNaN(r.value)),
      error: null,
    }
  } catch (e) {
    return { rows: [], error: String(e) }
  }
}

// Map namespace → friendly display name for the panel
const DISPLAY: Record<string, string> = {
  accounts: 'Account',
  balances: 'Balance',
  ledger: 'Ledger',
  payments: 'Payments',
  fx: 'FX',
  interest: 'Interest',
  party: 'Party',
  kyc: 'KYC',
  consent: 'Consent',
  aml: 'AML',
  dispute: 'Dispute',
  psd2: 'PSD2',
  audit: 'Audit',
  'customer-edge': 'Customer Edge',
  fraud: 'Fraud',
  platform: 'Copilot',
}

function savingsPotential(cpuEff: number | null, memEff: number | null): ServiceEfficiency['savingsPotential'] {
  const values = [cpuEff, memEff].filter((v): v is number => v !== null)
  if (!values.length) return 'unknown'
  const avg = values.reduce((s, v) => s + v, 0) / values.length
  if (avg < 30) return 'high'
  if (avg < 60) return 'medium'
  return 'low'
}

export async function GET() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 12_000)

  try {
    const ready = await fetch(`${prometheusBase()}/-/ready`, {
      signal: controller.signal,
    }).catch(() => null)

    if (!ready?.ok) {
      return NextResponse.json<RightSizingReport>({
        available: false,
        collectedAt: new Date().toISOString(),
        fleetCpuEfficiencyPct: null,
        fleetMemEfficiencyPct: null,
        highSavingsCount: 0,
        services: [],
        vpaHasData: false,
        error: 'prometheus is not ready',
        degraded: true,
      }, { headers: { 'Cache-Control': 'no-store' } })
    }

    // ── Queries: requested vs actual per namespace ────────────────────────────
    // kube-state-metrics (requests) + cAdvisor (actuals) are both part of
    // kube-prometheus-stack — no extra scrapers needed.
    const [cpuReqQ, cpuUsedQ, memReqQ, memUsedQ, vpaCpuQ, vpaMemQ] = await Promise.all([
      // CPU requested in millicores per namespace (sum across all containers)
      queryVector(
        'sum by (namespace) (kube_pod_container_resource_requests{resource="cpu",container!=""}) * 1000',
        controller.signal,
      ),
      // CPU actually used (rate over 5m), in millicores
      queryVector(
        'sum by (namespace) (rate(container_cpu_usage_seconds_total{container!="",container!="POD"}[5m])) * 1000',
        controller.signal,
      ),
      // Memory requested in MiB per namespace
      queryVector(
        'sum by (namespace) (kube_pod_container_resource_requests{resource="memory",container!=""}) / 1048576',
        controller.signal,
      ),
      // Memory actually used (working set) in MiB
      queryVector(
        'sum by (namespace) (container_memory_working_set_bytes{container!="",container!="POD"}) / 1048576',
        controller.signal,
      ),
      // VPA recommender target CPU (if VPA is installed and has data)
      queryVector(
        'sum by (namespace) (vpa_recommender_target_request_cpu_millicores)',
        controller.signal,
      ),
      // VPA recommender target memory (MiB)
      queryVector(
        'sum by (namespace) (vpa_recommender_target_request_memory_bytes) / 1048576',
        controller.signal,
      ),
    ])

    const toMap = (v: Vector) =>
      Object.fromEntries(v.rows.map(r => [r.metric.namespace ?? '', r.value]))

    // A failed query yields no rows, which is indistinguishable downstream from a namespace with
    // no data — so record WHICH queries failed rather than inferring it from the shape.
    const queries = {
      cpuRequested: cpuReqQ, cpuUsed: cpuUsedQ,
      memRequested: memReqQ, memUsed: memUsedQ,
      vpaCpu: vpaCpuQ, vpaMemory: vpaMemQ,
    }
    const failed = Object.entries(queries).flatMap(([n, q]) => (q.error ? [`${n}: ${q.error}`] : []))

    const cpuReqMap  = toMap(cpuReqQ)
    const cpuUsedMap = toMap(cpuUsedQ)
    const memReqMap  = toMap(memReqQ)
    const memUsedMap = toMap(memUsedQ)
    const vpaCpuMap  = toMap(vpaCpuQ)
    const vpaMemMap  = toMap(vpaMemQ)

    // Build per-namespace report — include all namespaces that have any data
    const allNs = new Set([
      ...Object.keys(cpuReqMap),
      ...Object.keys(cpuUsedMap),
      ...Object.keys(memReqMap),
      ...Object.keys(memUsedMap),
    ])

    const services: ServiceEfficiency[] = Array.from(allNs)
      .filter(ns => {
        // Include only openbank service namespaces (skip system, kube-*)
        const skip = ['kube-system', 'kube-public', 'kube-node-lease', 'argocd',
          'observability', 'vpa', 'falco', 'kyverno', 'cert-manager',
          'arc-runners', 'arc-systems', 'messaging', 'external-dns',
          'external-secrets', 'ingress-nginx']
        return !skip.includes(ns)
      })
      .map(ns => {
        const cpuR = cpuReqMap[ns] ?? null
        const cpuU = cpuUsedMap[ns] ?? null
        const memR = memReqMap[ns] ?? null
        const memU = memUsedMap[ns] ?? null

        const cpuEff = cpuR && cpuR > 0 && cpuU !== null
          ? Math.round((cpuU / cpuR) * 100)
          : null
        const memEff = memR && memR > 0 && memU !== null
          ? Math.round((memU / memR) * 100)
          : null

        return {
          namespace: ns,
          displayName: DISPLAY[ns] ?? ns,
          cpu: {
            requestedMillicores: cpuR !== null ? Math.round(cpuR) : null,
            usedMillicores:      cpuU !== null ? Math.round(cpuU) : null,
            efficiencyPct: cpuEff,
          },
          memory: {
            requestedMiB: memR !== null ? Math.round(memR) : null,
            usedMiB:      memU !== null ? Math.round(memU) : null,
            efficiencyPct: memEff,
          },
          savingsPotential: savingsPotential(cpuEff, memEff),
          vpaRecommendation: {
            cpuMillicores: vpaCpuMap[ns] != null ? Math.round(vpaCpuMap[ns]) : null,
            memoryMiB:     vpaMemMap[ns]  != null ? Math.round(vpaMemMap[ns])  : null,
          },
        }
      })
      .sort((a, b) => {
        // High savings first, then by cpu efficiency (lowest = most over-provisioned)
        const rank = { high: 0, medium: 1, low: 2, unknown: 3 }
        if (rank[a.savingsPotential] !== rank[b.savingsPotential]) {
          return rank[a.savingsPotential] - rank[b.savingsPotential]
        }
        return (a.cpu.efficiencyPct ?? 100) - (b.cpu.efficiencyPct ?? 100)
      })

    // Fleet averages (over services that have both requested + used data)
    const withCpu = services.filter(s => s.cpu.efficiencyPct !== null)
    const withMem = services.filter(s => s.memory.efficiencyPct !== null)
    const fleetCpu = withCpu.length
      ? Math.round(withCpu.reduce((s, v) => s + v.cpu.efficiencyPct!, 0) / withCpu.length)
      : null
    const fleetMem = withMem.length
      ? Math.round(withMem.reduce((s, v) => s + v.memory.efficiencyPct!, 0) / withMem.length)
      : null
    const highSavings = services.filter(s => s.savingsPotential === 'high').length

    return NextResponse.json<RightSizingReport>({
      available: true,
      collectedAt: new Date().toISOString(),
      fleetCpuEfficiencyPct: fleetCpu,
      fleetMemEfficiencyPct: fleetMem,
      highSavingsCount: highSavings,
      services,
      // Only meaningful when the VPA query actually answered: a failed query has no rows either.
      vpaHasData: vpaCpuQ.error === null && vpaCpuQ.rows.length > 0,
      error: failed.length ? failed.join('; ') : null,
      degraded: failed.length > 0,
    }, { headers: { 'Cache-Control': 'no-store' } })
  } finally {
    clearTimeout(timer)
  }
}
