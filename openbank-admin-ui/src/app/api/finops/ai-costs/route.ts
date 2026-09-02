// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'

// Langfuse→Prometheus bridge metrics (ADR-0112 P1). Falls back to mock data
// when the bridge is not yet deployed (sandbox / local dev).
export const dynamic = 'force-dynamic'
const PROMETHEUS_QUERY_TIMEOUT_MS = 5_000

export interface AgentCostEntry {
  agentId: string
  model: string
  tokensLast24h: number
  tokensLast7d: number | null
  costLast24hUsd: number
  costLast7dUsd: number
  budgetMonthlyUsd: number | null
  budgetUsedPct: number | null
  burnRate: 'low' | 'normal' | 'high' | 'exceeded'
  anomalyZ: number | null  // standard deviations from 30d mean; null = insufficient data
}

export interface AiCostsData {
  available: boolean
  collectedAt: string
  totalCostLast7dUsd: number
  totalCostLast30dUsd: number | null
  selfHostedPct: number | null
  coverage: MetricsCoverage
  agents: AgentCostEntry[]
  anomalies: FinOpsAnomaly[]
}

export interface MetricsCoverage {
  source: 'prometheus'
  retentionHours: number
  dataFrom: string
  dataTo: string
  lastSuccessfulLoad: string | null
  windows: Record<'24h' | '7d' | '30d', { requestedHours: number; availableHours: number; partial: boolean }>
}

function metricsCoverage(now: Date, successful: boolean): MetricsCoverage {
  const configured = Number(process.env.PROMETHEUS_RETENTION_HOURS ?? '12')
  const retentionHours = Number.isFinite(configured) && configured > 0 ? configured : 12
  const available = (requestedHours: number) => Math.min(requestedHours, retentionHours)
  return {
    source: 'prometheus',
    retentionHours,
    dataFrom: new Date(now.getTime() - retentionHours * 60 * 60 * 1000).toISOString(),
    dataTo: now.toISOString(),
    lastSuccessfulLoad: successful ? now.toISOString() : null,
    windows: {
      '24h': { requestedHours: 24, availableHours: available(24), partial: retentionHours < 24 },
      '7d': { requestedHours: 168, availableHours: available(168), partial: retentionHours < 168 },
      '30d': { requestedHours: 720, availableHours: available(720), partial: retentionHours < 720 },
    },
  }
}

export interface FinOpsAnomaly {
  id: string
  detectedAt: string
  detector: string  // D1–D5
  severity: 'warning' | 'critical'
  title: string
  rootCause: string | null  // LLM diagnosis; null = not yet diagnosed
  proposalPrUrl: string | null
  status: 'open' | 'proposed' | 'approved' | 'rejected' | 'resolved'
  estimatedMonthlySavingUsd: number | null
}

async function fetchFromPrometheus(query: string, prometheusUrl: string): Promise<number | null> {
  try {
    const url = `${prometheusUrl}/api/v1/query?query=${encodeURIComponent(query)}`
    const res = await fetch(url, { signal: AbortSignal.timeout(PROMETHEUS_QUERY_TIMEOUT_MS) })
    if (!res.ok) return null
    const json = await res.json() as { data?: { result?: Array<{ value?: [unknown, string] }> } }
    const result = json?.data?.result?.[0]?.value?.[1]
    return result != null ? parseFloat(result) : null
  } catch {
    return null
  }
}

export async function GET() {
  const now = new Date()
  const prometheusUrl = process.env.PROMETHEUS_URL ?? 'http://prometheus-operated.observability:9090'
  const langfuseUp = await fetchFromPrometheus('langfuse_up', prometheusUrl)
  const langfuseAvailable = langfuseUp === 1

  if (!langfuseAvailable) {
    // Return illustrative mock data when Langfuse→Prometheus bridge not yet deployed
    const mock: AiCostsData = {
      available: false,
      collectedAt: now.toISOString(),
      totalCostLast7dUsd: 0,
      totalCostLast30dUsd: null,
      selfHostedPct: null,
      coverage: metricsCoverage(now, false),
      agents: [],
      anomalies: [],
    }
    return NextResponse.json(mock)
  }

  // Live path: query Langfuse bridge metrics
  const agentIds = ['copilot', 'holmes-rca', 'finops-agent', 'fleet-monitor', 'code-review']
  const agentResults = await Promise.all(agentIds.map(async (agentId): Promise<AgentCostEntry | null> => {
    const [tokens24h, cost24h, cost7d, cost30d] = await Promise.all([
      fetchFromPrometheus(
        `sum(increase(langfuse_agent_tokens_total{agent_id="${agentId}"}[24h]))`, prometheusUrl
      ),
      fetchFromPrometheus(
        `sum(increase(langfuse_agent_tokens_total{agent_id="${agentId}"}[24h]) * on(model) group_left() langfuse_model_cost_per_token)`, prometheusUrl
      ),
      fetchFromPrometheus(
        `sum(increase(langfuse_agent_tokens_total{agent_id="${agentId}"}[7d]) * on(model) group_left() langfuse_model_cost_per_token)`, prometheusUrl
      ),
      fetchFromPrometheus(
        `sum(increase(langfuse_agent_tokens_total{agent_id="${agentId}"}[30d]) * on(model) group_left() langfuse_model_cost_per_token)`, prometheusUrl
      ),
    ])

    if (tokens24h == null && cost24h == null) return null

    // No monthly USD budget is configured in the current agents.yaml contract. Keep this
    // explicitly unavailable so consumers cannot render a fabricated budget percentage.
    const budgetMonthlyUsd: number | null = null
    const burnRate: AgentCostEntry['burnRate'] =
      cost24h == null ? 'normal'
      : cost24h > 10 ? 'exceeded'
      : cost24h > 5  ? 'high'
      : cost24h > 1  ? 'normal'
      : 'low'

    return {
      agentId,
      model: 'mixed',
      tokensLast24h: tokens24h ?? 0,
      tokensLast7d: null,
      costLast24hUsd: cost24h ?? 0,
      costLast7dUsd: cost7d ?? 0,
      budgetMonthlyUsd,
      budgetUsedPct: budgetMonthlyUsd != null && cost30d != null ? (cost30d / budgetMonthlyUsd) * 100 : null,
      burnRate,
      anomalyZ: null,
    }
  }))
  const agents = agentResults.filter((entry): entry is AgentCostEntry => entry !== null)

  const totalCost7d = agents.reduce((s, a) => s + a.costLast7dUsd, 0)

  return NextResponse.json({
    available: true,
    collectedAt: now.toISOString(),
    totalCostLast7dUsd: totalCost7d,
    totalCostLast30dUsd: null,
    // The current bridge exports aggregate token/cost series, not provider-placement evidence.
    selfHostedPct: null,
    coverage: metricsCoverage(now, true),
    agents,
    anomalies: [],
  } satisfies AiCostsData)
}
