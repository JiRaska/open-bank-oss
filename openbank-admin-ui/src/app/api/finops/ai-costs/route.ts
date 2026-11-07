// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'

// Langfuse→Prometheus bridge metrics (ADR-0112 P1). When the bridge is not deployed (sandbox /
// local dev) or Prometheus is unreachable, the response is `available: false` with NO figures:
// totals are null, not 0 — a consumer rendering a number would otherwise show "$0.00" for "unknown".
export const dynamic = 'force-dynamic'
const PROMETHEUS_QUERY_TIMEOUT_MS = 5_000

export interface AgentCostEntry {
  agentId: string
  model: string
  tokensLast24h: number | null
  tokensLast7d: number | null
  costLast24hUsd: number | null
  costLast7dUsd: number | null
  budgetMonthlyUsd: number | null
  budgetUsedPct: number | null
  burnRate: 'low' | 'normal' | 'high' | 'exceeded' | null
  anomalyZ: number | null  // standard deviations from 30d mean; null = insufficient data
}

export interface AiCostsData {
  /** Healthy bridge plus at least one measured metric; partial data may still be available. */
  available: boolean
  collectedAt: string
  /** null = incomplete 7d measurement across configured agents; measured zero remains 0. */
  totalCostLast7dUsd: number | null
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
  /** Complete query refresh only; retention may still be partial. Null if any requested metric is missing. */
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
    if (typeof result !== 'string' || result.trim() === '') return null
    const value = Number(result)
    return Number.isFinite(value) ? value : null
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
    const unavailable: AiCostsData = {
      available: false,
      collectedAt: now.toISOString(),
      totalCostLast7dUsd: null,
      totalCostLast30dUsd: null,
      selfHostedPct: null,
      coverage: metricsCoverage(now, false),
      agents: [],
      anomalies: [],
    }
    return NextResponse.json(unavailable)
  }

  // Live path: query Langfuse bridge metrics
  const agentIds = ['copilot', 'holmes-rca', 'finops-agent', 'fleet-monitor', 'code-review']
  const completeMeasurements: boolean[] = []
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

    completeMeasurements.push([tokens24h, cost24h, cost7d, cost30d].every(value => value != null))
    // Keep any measured period; a missing 24h value must not hide a valid 7d value.
    if ([tokens24h, cost24h, cost7d, cost30d].every(value => value == null)) return null

    // No monthly USD budget is configured in the current agents.yaml contract. Keep this
    // explicitly unavailable so consumers cannot render a fabricated budget percentage.
    const budgetMonthlyUsd: number | null = null
    const burnRate: AgentCostEntry['burnRate'] =
      cost24h == null ? null
      : cost24h > 10 ? 'exceeded'
      : cost24h > 5  ? 'high'
      : cost24h > 1  ? 'normal'
      : 'low'

    return {
      agentId,
      model: 'mixed',
      tokensLast24h: tokens24h,
      tokensLast7d: null,
      costLast24hUsd: cost24h,
      costLast7dUsd: cost7d,
      budgetMonthlyUsd,
      budgetUsedPct: budgetMonthlyUsd != null && cost30d != null ? (cost30d / budgetMonthlyUsd) * 100 : null,
      burnRate,
      anomalyZ: null,
    }
  }))
  const agents = agentResults.filter((entry): entry is AgentCostEntry => entry !== null)

  // A total covers the configured agent set; never label a partial sum as the full total.
  // Missing/failed periods remain null even when another period has measured data.
  const knownCosts7d = agentResults.map(entry => entry?.costLast7dUsd)
    .filter((value): value is number => value != null)
  const totalCost7d = knownCosts7d.length === agentIds.length
    ? knownCosts7d.reduce((sum, value) => sum + value, 0)
    : null

  return NextResponse.json({
    available: agents.length > 0,
    collectedAt: now.toISOString(),
    totalCostLast7dUsd: totalCost7d,
    totalCostLast30dUsd: null,
    // The current bridge exports aggregate token/cost series, not provider-placement evidence.
    selfHostedPct: null,
    coverage: metricsCoverage(now, completeMeasurements.every(Boolean)),
    agents,
    anomalies: [],
  } satisfies AiCostsData)
}
