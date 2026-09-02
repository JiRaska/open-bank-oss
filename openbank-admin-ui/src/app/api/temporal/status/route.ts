// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

function prometheusBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return process.env.PROMETHEUS_URL ?? 'http://localhost:9090'
}

/** Deliberately NOT a bare `number | null`.
 *
 *  `null` had to mean two different things: "the metric genuinely has no samples" (Temporal is not
 *  emitting it yet — honest, and the UI renders "no data") and "the query FAILED". That mattered
 *  most for `temporal_restarts`, whose value is the sole input to `temporalDeployed`: a Prometheus
 *  that is ready but answering 500 produced `temporalDeployed: false`, which the console renders
 *  as the confident claim that Temporal is not deployed — an outage reported as an architecture
 *  fact. Splitting the two lets an absent metric stay `null` while a failure is named (#7943). */
type Instant = { value: number | null; error: string | null }

async function queryInstant(query: string, signal: AbortSignal): Promise<Instant> {
  try {
    const url = `${prometheusBase()}/api/v1/query?query=${encodeURIComponent(query)}`
    const res = await fetch(url, { signal, headers: { Accept: 'application/json' } })
    if (!res.ok) return { value: null, error: `prometheus responded ${res.status}` }
    const json = await res.json() as {
      status: string
      data: { result: { metric: Record<string, string>; value: [number, string] }[] }
    }
    if (json.status !== 'success') return { value: null, error: `prometheus query status ${json.status}` }
    // No samples is a real answer, not a failure: the metric exists and has nothing to report.
    if (!json.data.result.length) return { value: null, error: null }
    const val = parseFloat(json.data.result[0].value[1])
    return { value: isNaN(val) ? null : val, error: null }
  } catch (e) {
    return { value: null, error: String(e) }
  }
}

type LabelVector = { rows: { labels: Record<string, string>; value: number }[]; error: string | null }

async function queryVector(query: string, signal: AbortSignal): Promise<LabelVector> {
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
      rows: json.data.result.flatMap(r => {
        const val = parseFloat(r.value[1])
        return isNaN(val) ? [] : [{ labels: r.metric, value: val }]
      }),
      error: null,
    }
  } catch (e) {
    return { rows: [], error: String(e) }
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
        // `null`, not `false`: with Prometheus unreachable this route has no basis for either
        // verdict about Temporal, and `false` is the one the console renders as a fact.
        temporalDeployed: null,
        error: 'prometheus is not ready',
        metrics: null,
      }, { headers: { 'Cache-Control': 'no-store' } })
    }

    // Detect if Temporal server is scraped by Prometheus at all.
    // Temporal server exposes metrics on port 9090 under the temporal namespace.
    const [
      temporalUpCountQ,
      workflowsScheduledQ,
      workflowsCompletedQ,
      workflowsFailedQ,
      workflowsTimedOutQ,
      activityScheduleLatencyP50Q,
      workflowTaskLatencyP50Q,
      requestLatencyQ,
      persistenceRequestsQ,
      workerTaskSlotsQ,
      workerTaskSlotsUsedQ,
      namespacesQ,
      workflowTypesQ,
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

    const queries = {
      temporalUp: temporalUpCountQ, workflowsScheduled: workflowsScheduledQ,
      workflowsCompleted: workflowsCompletedQ, workflowsFailed: workflowsFailedQ,
      workflowsTimedOut: workflowsTimedOutQ, activityScheduleLatency: activityScheduleLatencyP50Q,
      workflowTaskLatency: workflowTaskLatencyP50Q, requestLatency: requestLatencyQ,
      persistenceRequests: persistenceRequestsQ, workerTaskSlots: workerTaskSlotsQ,
      workerTaskSlotsUsed: workerTaskSlotsUsedQ, namespaces: namespacesQ,
      workflowTypes: workflowTypesQ,
    }
    const failed = Object.entries(queries).flatMap(([n, q]) => (q.error ? [`${n}: ${q.error}`] : []))

    const temporalUpCount = temporalUpCountQ.value
    const workflowsScheduled = workflowsScheduledQ.value
    const workflowsCompleted = workflowsCompletedQ.value
    const workflowsFailed = workflowsFailedQ.value
    const workflowsTimedOut = workflowsTimedOutQ.value
    const activityScheduleLatencyP50 = activityScheduleLatencyP50Q.value
    const workflowTaskLatencyP50 = workflowTaskLatencyP50Q.value
    const requestLatency = requestLatencyQ.value
    const persistenceRequests = persistenceRequestsQ.value
    const workerTaskSlots = workerTaskSlotsQ.value
    const workerTaskSlotsUsed = workerTaskSlotsUsedQ.value
    const namespaces = namespacesQ.rows
    const workflowTypes = workflowTypesQ.rows

    // temporal_restarts is absent (count → vector(0)) only when Temporal is not scraped yet — but
    // ONLY if the query answered. When it failed we know nothing, and `null` says so rather than
    // letting an outage render as "Temporal is not deployed".
    const temporalDeployed = temporalUpCountQ.error !== null ? null : (temporalUpCount ?? 0) > 0

    const activeNamespaces = namespaces.map(n => n.labels.namespace ?? 'unknown')

    return NextResponse.json({
      available: true,
      temporalDeployed,
      // Null when every query answered — an empty/absent metric set under a null error really is
      // Temporal having nothing to report, not this route failing to ask.
      error: failed.length ? failed.join('; ') : null,
      degraded: failed.length > 0,
      metrics: temporalDeployed === true ? {
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
