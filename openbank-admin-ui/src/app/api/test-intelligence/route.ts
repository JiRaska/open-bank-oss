// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { promises as fs } from 'fs'
import path from 'path'
import { NextResponse } from 'next/server'
import type { EvidenceState, TestIntelligenceReport } from '@/lib/types/test-intelligence'
import { enforceRuntimeFreshness } from '@/lib/test-intelligence-freshness'

export const dynamic = 'force-dynamic'

const reportFile = () => process.env.OPENBANK_TEST_INTELLIGENCE
  ?? path.resolve(process.cwd(), 'test-intelligence.json')

const emptyReport = (error: string): TestIntelligenceReport => ({
  schemaVersion: 1,
  collectedAt: new Date(0).toISOString(),
  components: [], contracts: [], mutations: [], performance: [], syntheticJourneys: [], clientExperiences: [], history: [], runHistory: [], testCases: [],
  totals: {
    components: 0, componentsWithExecutionEvidence: 0, moneyPathComponents: 0,
    failingEvidence: 0, missingEvidence: 0, staleEvidence: 0,
  },
  warnings: [error],
})

type PrometheusVector = { status?: string; data?: { result?: { value?: [number, string] }[] } }
type PrometheusLabelVector = { status?: string; data?: { result?: { metric?: Record<string, string>; value?: [number, string] }[] } }
type TempoSearch = { traces?: Array<{ traceID?: string }> }

// Journey ids arrive from the build-time bundled report, so they are file data reaching an
// outbound Prometheus request. Kubernetes object names are already restricted to this
// alphabet, which makes rejection — never escaping — the correct handling: an id that cannot
// be a cronjob name has no live counterpart to query, and quoting it would hide that.
const KUBERNETES_NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/

function cronjobSelector(journeyId: string): string | null {
  const name = `journey-${journeyId}`
  return KUBERNETES_NAME.test(name) ? name : null
}

function prometheusBase(): string | null {
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return process.env.PROMETHEUS_URL ?? null
}

function tempoBase(): string | null {
  if (process.env.SERVICES_HOST === 'container') return 'http://tempo:3200'
  return process.env.TEMPO_URL ?? null
}

async function queryTempoMobileTraces(base: string): Promise<{ count: number; truncated: boolean } | null> {
  const end = Math.floor(Date.now() / 1000)
  const query = new URLSearchParams({
    tags: 'service.name="openbank-app"', start: String(end - 7 * 86400), end: String(end), limit: '1000',
  })
  try {
    const response = await fetch(`${base}/api/search?${query}`, {
      headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(8000), cache: 'no-store',
    })
    if (!response.ok) return null
    const payload = await response.json() as TempoSearch
    if (!Array.isArray(payload.traces)) return null
    const count = new Set(payload.traces.flatMap(item => item.traceID ? [item.traceID] : [])).size
    return { count, truncated: payload.traces.length >= 1000 }
  } catch { return null }
}

async function queryPrometheusRuns(base: string, cronjob: string): Promise<Array<{ id: string; state: 'passed' | 'failed'; observedAt: string }>> {
  // Status gauges retain value 1 and their query sample timestamp advances on every scrape.
  // Use the completion-time gauge as the value, and attach an explicit result label, otherwise
  // a week-old retained Job is rendered as a run that happened "now" and its state depends on
  // Prometheus retaining the implementation-specific __name__ label in a union response.
  const completed = `kube_job_status_completion_time{namespace="observability",job_name=~"${cronjob}.*"}`
  const query = `label_replace(max by (job_name) (${completed} and on(namespace,job_name) (kube_job_status_succeeded{namespace="observability",job_name=~"${cronjob}.*"} == 1)), "openbank_evidence_state", "passed", "job_name", ".*") or label_replace(max by (job_name) (${completed} and on(namespace,job_name) (kube_job_status_failed{namespace="observability",job_name=~"${cronjob}.*"} == 1)), "openbank_evidence_state", "failed", "job_name", ".*")`
  try {
    const response = await fetch(`${base}/api/v1/query?query=${encodeURIComponent(query)}`, {
      headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(2500), cache: 'no-store',
    })
    if (!response.ok) return []
    const payload = await response.json() as PrometheusLabelVector
    return (payload.data?.result ?? []).flatMap(item => {
      const id = item.metric?.job_name
      const completedAt = Number(item.value?.[1] ?? 0)
      const state = item.metric?.openbank_evidence_state
      if (!id || !Number.isFinite(completedAt) || completedAt <= 0 || (state !== 'passed' && state !== 'failed')) return []
      const evidenceState: 'passed' | 'failed' = state === 'failed' ? 'failed' : 'passed'
      return [{ id, state: evidenceState, observedAt: new Date(completedAt * 1000).toISOString() }]
    }).sort((left, right) => right.observedAt.localeCompare(left.observedAt)).slice(0, 10)
  } catch { return [] }
}

function freshnessLimitSeconds(schedule: string | null): number {
  if (!schedule) return 3600
  const everyMinutes = schedule.match(/^\*\/(\d+) \* \* \* \*$/)?.[1]
  if (everyMinutes) return Math.max(900, Number(everyMinutes) * 60 * 3)
  if (/^\d+ \* \* \* \*$/.test(schedule)) return 3 * 3600
  if (/^\d+ \d+ \* \* \*$/.test(schedule)) return 3 * 86400
  return 3600
}

async function queryPrometheus(base: string, query: string): Promise<number | null> {
  try {
    const response = await fetch(`${base}/api/v1/query?query=${encodeURIComponent(query)}`, {
      headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(2500), cache: 'no-store',
    })
    if (!response.ok) return null
    const payload = await response.json() as PrometheusVector
    const raw = payload.data?.result?.[0]?.value?.[1]
    if (payload.status !== 'success' || raw === undefined) return null
    const value = Number(raw)
    return Number.isFinite(value) ? value : null
  } catch { return null }
}

async function attachLiveJourneys(report: TestIntelligenceReport): Promise<TestIntelligenceReport> {
  const base = prometheusBase()
  if (!base) return report
  const nowSeconds = Date.now() / 1000
  const syntheticJourneys = await Promise.all(report.syntheticJourneys.map(async journey => {
    if (journey.status !== 'active') return journey
    const cronjob = cronjobSelector(journey.id)
    if (!cronjob) return journey
    // A failure must remain visible for the same evidence window used to judge a
    // successful run fresh. A fixed 30-minute window made a failed hourly or daily
    // journey look healthy again while its last successful run was still nominally fresh.
    const failureWindowSeconds = freshnessLimitSeconds(journey.schedule)
    const [scheduled, successful, failures, activeJobs, recentRuns] = await Promise.all([
      queryPrometheus(base, `max(kube_cronjob_status_last_schedule_time{namespace="observability",cronjob="${cronjob}"})`),
      queryPrometheus(base, `max(kube_cronjob_status_last_successful_time{namespace="observability",cronjob="${cronjob}"})`),
      // kube-state-metrics continues exporting terminal Job status until the Job is garbage
      // collected. A historical failed Job therefore remains `1` forever; selecting it with
      // max_over_time makes a later successful schedule look failed. Join on completion time so
      // only a Job which both failed AND completed inside the window is a current failure.
      // An empty vector means no failed Job in the window, not an unavailable Prometheus
      // observation. Preserve that distinction for both the state machine and the UI count.
      queryPrometheus(base, `max((kube_job_status_failed{namespace="observability",job_name=~"${cronjob}.*"} > 0) and on(namespace,job_name) (time() - kube_job_status_completion_time{namespace="observability",job_name=~"${cronjob}.*"} < ${failureWindowSeconds})) or vector(0)`),
      // A reachable Prometheus with no active Jobs must yield zero, not an unavailable value.
      queryPrometheus(base, `sum(kube_job_status_active{namespace="observability",job_name=~"${cronjob}.*"}) or vector(0)`),
      queryPrometheusRuns(base, cronjob),
    ])
    const observedAt = new Date().toISOString()
    const freshnessSeconds = successful === null ? null : Math.max(0, nowSeconds - successful)
    const state: EvidenceState = failures !== null && failures > 0 ? 'failed'
      : successful === null && activeJobs !== null && activeJobs > 0 ? 'unknown'
        : successful === null ? 'not-run'
        : freshnessSeconds !== null && freshnessSeconds > freshnessLimitSeconds(journey.schedule) ? 'stale' : 'passed'
    return {
      ...journey, state,
      live: {
        source: 'prometheus' as const, observedAt,
        lastScheduledAt: scheduled === null ? null : new Date(scheduled * 1000).toISOString(),
        lastSuccessfulAt: successful === null ? null : new Date(successful * 1000).toISOString(),
        failuresWithinWindow: failures, failureWindowSeconds, activeJobs, freshnessSeconds, recentRuns,
      },
    }
  }))
  return { ...report, syntheticJourneys }
}

async function attachLiveClientExperience(report: TestIntelligenceReport): Promise<TestIntelligenceReport> {
  const metricsBase = prometheusBase()
  const tracesBase = tempoBase()
  if (!metricsBase && !tracesBase) return report
  const mobile = report.clientExperiences.find(client => client.id === 'openbank-app')
  if (!mobile) return report

  // Tempo's span-metrics prove arrival after the hardened, consent-gated RUM gateway.
  // `or vector(0)` distinguishes a reachable Prometheus with no sampled mobile signal
  // from an unavailable query. Zero is normal and must not become a failed CI verdict.
  const [tempoTraces, spanCounterIncrements, errorSpans] = await Promise.all([
    tracesBase ? queryTempoMobileTraces(tracesBase) : Promise.resolve(null),
    metricsBase ? queryPrometheus(metricsBase, 'sum(increase(traces_spanmetrics_calls_total{service=~"openbank-app.*"}[7d])) or vector(0)') : Promise.resolve(null),
    metricsBase ? queryPrometheus(metricsBase, 'sum(increase(traces_spanmetrics_calls_total{service=~"openbank-app.*",status_code="STATUS_CODE_ERROR"}[7d])) or vector(0)') : Promise.resolve(null),
  ])
  if (tempoTraces === null && spanCounterIncrements === null) return report

  const observedAt = new Date().toISOString()
  const sampled = tempoTraces?.count ?? Math.max(0, Math.round(spanCounterIncrements ?? 0))
  const errors = errorSpans === null ? null : Math.max(0, Math.round(errorSpans))
  const source = tempoTraces === null ? 'prometheus' as const : 'tempo' as const
  const countLabel = tempoTraces?.truncated ? `at least ${sampled}` : String(sampled)
  const clientExperiences = report.clientExperiences.map(client => client.id !== mobile.id ? client : ({
    ...client,
    rum: {
      ...client.rum,
      state: sampled > 0 ? 'passed' as const : 'not-run' as const,
      source,
      observedAt,
      sampledSpansLast7d: sampled,
      errorSpansLast7d: errors,
      detail: sampled > 0
        ? `${countLabel} sampled mobile RUM trace(s) reached Tempo in the last 7 days${errors === null ? '' : `; ${errors} span-metric increment(s) carried an error status`}. This proves telemetry arrival, not customer traffic volume or test success.`
        : 'No sampled mobile RUM span reached Tempo in the last 7 days. Consent is opt-in, so this is an explicit absent runtime observation rather than a failed CI test.',
    },
  }))
  return { ...report, clientExperiences }
}

export async function GET(): Promise<NextResponse> {
  try {
    const parsed = JSON.parse(await fs.readFile(reportFile(), 'utf8')) as TestIntelligenceReport
    if (parsed.schemaVersion !== 1 || !Array.isArray(parsed.components)) {
      return NextResponse.json(emptyReport('Unsupported test-intelligence report schema'))
    }
    const compatible = {
      ...parsed, history: parsed.history ?? [], runHistory: parsed.runHistory ?? [], testCases: parsed.testCases ?? [], clientExperiences: parsed.clientExperiences ?? [],
      components: parsed.components.map(component => ({
        ...component,
        testInfrastructure: component.testInfrastructure ?? { declared: [], observed: [] },
      })),
    }
    const current = enforceRuntimeFreshness(compatible)
    const withJourneys = await attachLiveJourneys(current)
    return NextResponse.json(await attachLiveClientExperience(withJourneys), { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json(emptyReport('test-intelligence.json is not bundled'))
  }
}
