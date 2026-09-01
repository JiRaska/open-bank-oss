// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
      workflowTypes,
    ] = await Promise.all([
      // Metric names below are the Temporal SERVER (tally/prometheus) names as
      // scraped by the temporal-server PodMonitor (prefixed temporal_ at scrape
      // time). They are NOT the SDK/client names — the server emits e.g.
      // temporal_restarts (not _server_start_count), temporal_service_requests
      // (not _request_total), temporal_workflow_success (not _completed_count),
      // temporal_service_latency (not _request_latency), temporal_persistence_requests
      // (not _total). See docs.temporal.io/references/cluster-metrics.
      //
      // temporal_restarts is emitted once per process start, so its presence is a
      // reliable "Temporal is up and scraped" signal.
      queryInstant('count(temporal_restarts) or vector(0)', controller.signal),
      queryInstant('sum(increase(temporal_service_requests{operation="StartWorkflowExecution"}[1h])) or vector(0)', controller.signal),
      queryInstant('sum(increase(temporal_workflow_success[1h])) or vector(0)', controller.signal),
      queryInstant('sum(increase(temporal_workflow_failed[1h])) or vector(0)', controller.signal),
      queryInstant('sum(increase(temporal_workflow_timeout[1h])) or vector(0)', controller.signal),
      // schedule-to-start latency + worker task slots are Temporal SDK (worker)
      // metrics emitted by the application services' workers, not the server, so
      // they are absent until the services expose SDK metrics — these resolve to
      // null and the UI shows them as "no data" (honest) until that lands.
      queryInstant('histogram_quantile(0.5, rate(temporal_activity_schedule_to_start_latency_bucket[5m]))', controller.signal),
      queryInstant('histogram_quantile(0.5, rate(temporal_task_schedule_to_start_latency_bucket[5m]))', controller.signal),
      queryInstant('histogram_quantile(0.99, rate(temporal_service_latency_bucket[5m]))', controller.signal),
      queryInstant('sum(rate(temporal_persistence_requests[5m]))', controller.signal),
      queryInstant('sum(temporal_worker_task_slots_available)', controller.signal),
      queryInstant('sum(temporal_worker_task_slots_used)', controller.signal),
      queryVector('group by (namespace) (temporal_workflow_success)', controller.signal),
      queryVector('sum by (namespace, workflow_type) (increase(temporal_workflow_success[1h]))', controller.signal),
    ])

    // temporal_restarts is absent (count → vector(0)) only when Temporal is not scraped yet
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
        workflowTypes: workflowTypes.map(row => ({
          namespace: row.labels.namespace ?? 'unknown',
          workflowType: row.labels.workflow_type ?? 'unknown',
          completed1h: Math.round(row.value),
        })).filter(row => row.workflowType !== 'unknown'),
      } : null,
    }, { headers: { 'Cache-Control': 'no-store' } })
  } finally {
    clearTimeout(timer)
  }
}
