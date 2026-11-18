// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0384 D4 (first slice): customer MCP channel health for the delegation console.
//
// A FIXED set of instant queries over the D1 series mcp-service emits — the browser never sends
// PromQL (ADR-0056: the BFF is the only path, and a free-text query proxy would let any operator
// read any series). No party, grant or key is a label on those series, so nothing here can name a
// customer; per-party history is the warehouse's job (ADR-0384 D3), not this route's.
//
// Absence is reported as null, NEVER 0. Until the ADR-0383 customer path exists the customer
// series do not exist, and "0 customer calls" would read as "the channel is open and idle" when
// the truth is "nothing has measured it yet". Same rule as security/kpis/metrics.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { inCluster } from '@/lib/discovery'

export const dynamic = 'force-dynamic'
export const revalidate = 0

// NOT exported: a route module may export only Next's route fields (gate route-exports).
const TIMEOUT_MS = 4000

function prometheusBase(): string {
  if (process.env.PROMETHEUS_URL) return process.env.PROMETHEUS_URL.replace(/\/$/, '')
  if (inCluster()) return 'http://kube-prometheus-stack-prometheus.observability.svc:9090'
  return process.env.SERVICES_HOST === 'container' ? 'http://prometheus:9090' : 'http://localhost:9090'
}

const QUERIES = {
  customerCalls: 'sum(increase(openbank_mcp_tool_calls_total{channel="customer",synthetic="false"}[1h]))',
  syntheticCalls: 'sum(increase(openbank_mcp_tool_calls_total{channel="customer",synthetic="true"}[1h]))',
  agentCalls: 'sum(increase(openbank_mcp_tool_calls_total{channel=~"agent|operator"}[1h]))',
  rejectsByLayer: 'sum by (layer) (increase(openbank_mcp_customer_rejected_total{synthetic="false"}[1h]))',
  firingAlerts: 'count by (alertname) (ALERTS{alertstate="firing",alertname=~"McpCustomer.*"})',
} as const

type PromVector = { metric: Record<string, string>; value: [number, string] }[]

async function query(base: string, q: string): Promise<PromVector | null> {
  try {
    const res = await fetch(`${base}/api/v1/query?query=${encodeURIComponent(q)}`, {
      signal: AbortSignal.timeout(TIMEOUT_MS),
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    })
    if (!res.ok) return null
    const body = (await res.json()) as { status?: string; data?: { resultType?: string; result?: PromVector } }
    if (body.status !== 'success' || body.data?.resultType !== 'vector') return null
    return body.data.result ?? []
  } catch {
    return null
  }
}

/** One scalar from a `sum(...)`: null when the series is absent (empty vector) or the query failed. */
function scalar(v: PromVector | null): number | null {
  if (!v || v.length === 0) return null
  const n = Number(v[0].value[1])
  return Number.isFinite(n) ? Math.round(n) : null
}

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }

  const base = prometheusBase()
  const [customer, synthetic, agent, rejects, alerts] = await Promise.all([
    query(base, QUERIES.customerCalls),
    query(base, QUERIES.syntheticCalls),
    query(base, QUERIES.agentCalls),
    query(base, QUERIES.rejectsByLayer),
    query(base, QUERIES.firingAlerts),
  ])

  // Prometheus itself unreachable: every query failed. Distinct from "reachable, no series".
  if ([customer, synthetic, agent, rejects, alerts].every(r => r === null)) {
    return NextResponse.json({ state: 'unavailable' })
  }

  const rejectsByLayer: Record<string, number> = {}
  for (const s of rejects ?? []) {
    const n = Number(s.value[1])
    if (s.metric.layer && Number.isFinite(n)) rejectsByLayer[s.metric.layer] = Math.round(n)
  }

  return NextResponse.json({
    state: 'ok',
    window: '1h',
    customerCalls: scalar(customer),
    syntheticCalls: scalar(synthetic),
    agentCalls: scalar(agent),
    // null = no customer refusal series exists yet; {} would wrongly read as "none refused".
    rejectsByLayer: rejects && rejects.length > 0 ? rejectsByLayer : null,
    firingAlerts: (alerts ?? []).map(a => a.metric.alertname).filter(Boolean).sort(),
    // ADR-0384 D1 puts the active-grant gauge in a delegation-service projection that is not built
    // yet. Reported as not measured rather than derived from something that is not the same thing.
    activeGrants: null,
  })
}
