// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

function prometheusBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return process.env.PROMETHEUS_URL ?? 'http://localhost:9090'
}

async function queryInstant(query: string, signal: AbortSignal): Promise<number | null> {
  try {
    const url = `${prometheusBase()}/api/v1/query?query=${encodeURIComponent(query)}`
    const res = await fetch(url, { signal, headers: { Accept: 'application/json' } })
    if (!res.ok) return null
    const json = await res.json() as {
      status: string
      data: { result: { metric: Record<string, string>; value: [number, string] }[] }
    }
    if (json.status !== 'success' || !json.data.result.length) return null
    const val = parseFloat(json.data.result[0].value[1])
    return isNaN(val) ? null : val
  } catch {
    return null
  }
}

async function queryVector(query: string, signal: AbortSignal): Promise<{ labels: Record<string, string>; value: number }[]> {
  try {
    const url = `${prometheusBase()}/api/v1/query?query=${encodeURIComponent(query)}`
    const res = await fetch(url, { signal, headers: { Accept: 'application/json' } })
    if (!res.ok) return []
    const json = await res.json() as {
      status: string
      data: { result: { metric: Record<string, string>; value: [number, string] }[] }
    }
    if (json.status !== 'success') return []
    return json.data.result.flatMap(r => {
      const val = parseFloat(r.value[1])
      return isNaN(val) ? [] : [{ labels: r.metric, value: val }]
    })
  } catch {
    return []
  }
}

export async function GET() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 8000)

  try {
    // Check if Prometheus is reachable
    const promReady = await fetch(`${prometheusBase()}/-/ready`, {
      signal: controller.signal,
    }).catch(() => null)
    const prometheusUp = !!(promReady?.ok)

    if (!prometheusUp) {
      return NextResponse.json({
        available: false,
        temporalDeployed: false,
        metrics: null,
      }, { headers: { 'Cache-Control': 'no-store' } })
    }

    // Detect if Temporal server is scraped by Prometheus at all.
    // Temporal server exposes metrics on port 9090 under the temporal namespace.
    const [
      temporalUpCount,
      workflowsScheduled,
      workflowsCompleted,
      workflowsFailed,
      workflowsTimedOut,
      activityScheduleLatencyP50,
      workflowTaskLatencyP50,
      requestLatency,
      persistenceRequests,
      workerTaskSlots,
      workerTaskSlotsUsed,
      namespaces,
    ] = await Promise.all([
      // temporal_server_start_count exists if Temporal is running and scraped
      queryInstant('count(temporal_server_start_count) or vector(0)', controller.signal),
      queryInstant('sum(increase(temporal_request_total{operation="StartWorkflowExecution"}[1h])) or vector(0)', controller.signal),
      queryInstant('sum(increase(temporal_workflow_completed_count[1h])) or vector(0)', controller.signal),
      queryInstant('sum(increase(temporal_workflow_failed_count[1h])) or vector(0)', controller.signal),
      queryInstant('sum(increase(temporal_workflow_timeout_count[1h])) or vector(0)', controller.signal),
      queryInstant('histogram_quantile(0.5, rate(temporal_activity_task_schedule_to_start_latency_bucket[5m]))', controller.signal),
      queryInstant('histogram_quantile(0.5, rate(temporal_workflow_task_schedule_to_start_latency_bucket[5m]))', controller.signal),
      queryInstant('histogram_quantile(0.99, rate(temporal_request_latency_bucket[5m]))', controller.signal),
      queryInstant('sum(rate(temporal_persistence_requests_total[5m]))', controller.signal),
      queryInstant('sum(temporal_worker_task_slots_available)', controller.signal),
      queryInstant('sum(temporal_worker_task_slots_used)', controller.signal),
      queryVector('group by (namespace) (temporal_namespace_active_count)', controller.signal),
    ])

    // If temporal_server_start_count returns 0 (from vector(0)), Temporal not yet scraped
    const temporalDeployed = (temporalUpCount ?? 0) > 0

    const activeNamespaces = namespaces.map(n => n.labels.namespace ?? 'unknown')

    return NextResponse.json({
      available: true,
      temporalDeployed,
      metrics: temporalDeployed ? {
        workflows: {
          scheduled1h: Math.round(workflowsScheduled ?? 0),
          completed1h: Math.round(workflowsCompleted ?? 0),
          failed1h: Math.round(workflowsFailed ?? 0),
          timedOut1h: Math.round(workflowsTimedOut ?? 0),
        },
        latency: {
          activityScheduleToStartMs: activityScheduleLatencyP50 !== null
            ? Math.round(activityScheduleLatencyP50 * 1000)
            : null,
          workflowTaskScheduleToStartMs: workflowTaskLatencyP50 !== null
            ? Math.round(workflowTaskLatencyP50 * 1000)
            : null,
          serverRequestP99Ms: requestLatency !== null
            ? Math.round(requestLatency * 1000)
            : null,
        },
        persistence: {
          requestsPerSec: persistenceRequests !== null
            ? Math.round(persistenceRequests * 100) / 100
            : null,
        },
        workers: {
          totalSlotsAvailable: workerTaskSlots !== null ? Math.round(workerTaskSlots) : null,
          slotsUsed: workerTaskSlotsUsed !== null ? Math.round(workerTaskSlotsUsed) : null,
        },
        namespaces: activeNamespaces.length > 0 ? activeNamespaces : ['openbank-default'],
      } : null,
    }, { headers: { 'Cache-Control': 'no-store' } })
  } finally {
    clearTimeout(timer)
  }
}
