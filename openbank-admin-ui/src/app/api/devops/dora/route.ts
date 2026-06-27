// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

// DORA classification thresholds (Google State of DevOps 2023)
// Deployment Frequency
const DF_ELITE = 1       // per day
const DF_HIGH  = 1 / 7   // per day (weekly)
const DF_MED   = 1 / 30  // per day (monthly)
// Lead Time for Changes (hours)
const LT_ELITE = 1
const LT_HIGH  = 24
const LT_MED   = 168     // 1 week
// Change Failure Rate (%)
const CFR_ELITE = 5
const CFR_HIGH  = 10
const CFR_MED   = 15
// MTTR (hours)
const MTTR_ELITE = 1
const MTTR_HIGH  = 24
const MTTR_MED   = 168

type DoraLevel = 'elite' | 'high' | 'medium' | 'low'

function classifyDf(perDay: number): DoraLevel {
  if (perDay >= DF_ELITE) return 'elite'
  if (perDay >= DF_HIGH)  return 'high'
  if (perDay >= DF_MED)   return 'medium'
  return 'low'
}
function classifyLt(hours: number): DoraLevel {
  if (hours <= LT_ELITE) return 'elite'
  if (hours <= LT_HIGH)  return 'high'
  if (hours <= LT_MED)   return 'medium'
  return 'low'
}
function classifyCfr(pct: number): DoraLevel {
  if (pct <= CFR_ELITE) return 'elite'
  if (pct <= CFR_HIGH)  return 'high'
  if (pct <= CFR_MED)   return 'medium'
  return 'low'
}
function classifyMttr(hours: number): DoraLevel {
  if (hours <= MTTR_ELITE) return 'elite'
  if (hours <= MTTR_HIGH)  return 'high'
  if (hours <= MTTR_MED)   return 'medium'
  return 'low'
}

function overallLevel(levels: DoraLevel[]): DoraLevel {
  const rank = { elite: 4, high: 3, medium: 2, low: 1 } as const
  const min = Math.min(...levels.map(l => rank[l]))
  return (['low', 'medium', 'high', 'elite'] as DoraLevel[])[min - 1]
}

// Deployment Frequency + Lead Time come from a build-time git-derived snapshot
// (scripts/collect-dora.mjs → dora.json), not a live GitHub call: the admin-ui is
// a read-only consumer and holds no GITHUB_TOKEN (ADR-0061, mirrors the cost/test
// snapshots). leadTimeHours is null on a squash-merged trunk (Phase 2).
interface DoraSnapshot {
  available: boolean
  source: string
  windowDays: number
  collectedAt: string
  deploymentCount: number
  deploymentFrequencyPerDay: number | null
  leadTimeHours: number | null
  leadTimeReason?: string | null
  recentDeployments: { date: string; service: string; sha: string }[]
}

async function readDoraSnapshot(): Promise<DoraSnapshot | null> {
  const file = process.env.OPENBANK_DORA_REPORT ?? path.resolve(process.cwd(), 'dora.json')
  try {
    const raw = await fs.readFile(file, 'utf8')
    return JSON.parse(raw) as DoraSnapshot
  } catch {
    return null
  }
}

function prometheusBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return process.env.PROMETHEUS_URL ?? 'http://localhost:9090'
}

async function queryPrometheus(query: string, signal: AbortSignal): Promise<number | null> {
  try {
    const url = `${prometheusBase()}/api/v1/query?query=${encodeURIComponent(query)}`
    const res = await fetch(url, { signal, headers: { Accept: 'application/json' } })
    if (!res.ok) return null
    const json = await res.json() as { status: string; data: { result: { value: [number, string] }[] } }
    if (json.status !== 'success' || !json.data.result.length) return null
    const val = parseFloat(json.data.result[0].value[1])
    return isNaN(val) ? null : val
  } catch {
    return null
  }
}

export async function GET() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 10_000)

  try {
    // ── Git snapshot: Deployment Frequency + Lead Time ────────────────────────
    const dora = await readDoraSnapshot()
    const gitAvailable = dora?.available === true
    const deploymentFrequencyPerDay = gitAvailable ? dora!.deploymentFrequencyPerDay : null
    const leadTimeHours = gitAvailable ? dora!.leadTimeHours : null
    const leadTimeReason = dora?.leadTimeReason ?? null
    const deploymentCount30d = gitAvailable ? dora!.deploymentCount : 0
    const recentDeployments: { date: string; service: string; sha: string }[] =
      gitAvailable ? dora!.recentDeployments.slice(0, 10) : []

    // ── Prometheus: Change Failure Rate + MTTR ──────────────────────────────
    let changeFailureRate: number | null = null
    let mttrHoursFromPrometheus: number | null = null
    let prometheusAvailable = false

    try {
      const ready = await fetch(`${prometheusBase()}/-/ready`, {
        signal: controller.signal,
      }).catch(() => null)

      if (ready?.ok) {
        prometheusAvailable = true

        // Error rate over last 30d as proxy for CFR
        const errorRate = await queryPrometheus(
          'sum(increase(http_server_requests_seconds_count{status=~"5.."}[30d])) / sum(increase(http_server_requests_seconds_count[30d])) * 100',
          controller.signal,
        )
        if (errorRate !== null) changeFailureRate = Math.min(Math.round(errorRate * 10) / 10, 100)

        // MTTR: Alertmanager does not persist resolved-alert history (default
        // 5-min in-memory expiry). Best available signal: Prometheus ALERTS
        // time series. sum_over_time counts samples where alertstate=firing;
        // multiply by the assumed 15 s scrape interval to get total seconds.
        // Incident count = distinct label combos that fired ≥ once in 30 d.
        // Limitation: one alertname that fires/resolves 5 times counts as 1
        // incident — MTTR is therefore a 30-day average, NOT per-incident.
        const [firingSeconds, incidentCount] = await Promise.all([
          queryPrometheus(
            'sum(sum_over_time(ALERTS{alertstate="firing",severity=~"critical|warning"}[30d])) * 15',
            controller.signal,
          ),
          queryPrometheus(
            'count(count_over_time(ALERTS{alertstate="firing",severity=~"critical|warning"}[30d]) > 0)',
            controller.signal,
          ),
        ])
        if (firingSeconds !== null && incidentCount !== null && incidentCount > 0) {
          mttrHoursFromPrometheus = Math.round((firingSeconds / incidentCount / 3600) * 10) / 10
        }
      }
    } catch { /* Prometheus unreachable */ }

    // ── Compute DORA levels ──────────────────────────────────────────────────
    const dfLevel   = deploymentFrequencyPerDay !== null ? classifyDf(deploymentFrequencyPerDay) : null
    const ltLevel   = leadTimeHours              !== null ? classifyLt(leadTimeHours)             : null
    const cfrLevel  = changeFailureRate          !== null ? classifyCfr(changeFailureRate)        : null

    const mttrHours: number | null = mttrHoursFromPrometheus
    const mttrLevel: DoraLevel | null = mttrHours !== null ? classifyMttr(mttrHours) : null

    const knownLevels = [dfLevel, ltLevel, cfrLevel, mttrLevel].filter((l): l is DoraLevel => l !== null)
    const overall: DoraLevel | null = knownLevels.length >= 2 ? overallLevel(knownLevels) : null

    return NextResponse.json({
      overall,
      metrics: {
        deploymentFrequency: {
          perDay: deploymentFrequencyPerDay,
          count30d: deploymentCount30d,
          level: dfLevel,
          description: deploymentFrequencyPerDay !== null
            ? `${deploymentFrequencyPerDay.toFixed(2)} deployments/day`
            : null,
        },
        leadTime: {
          hours: leadTimeHours !== null ? Math.round(leadTimeHours * 10) / 10 : null,
          level: ltLevel,
          description: leadTimeHours !== null
            ? leadTimeHours < 1 ? `${Math.round(leadTimeHours * 60)} min`
              : leadTimeHours < 24 ? `${Math.round(leadTimeHours)}h`
              : `${Math.round(leadTimeHours / 24)}d`
            : null,
          note: leadTimeReason,
        },
        changeFailureRate: {
          pct: changeFailureRate,
          level: cfrLevel,
          note: 'Proxy: 30-day HTTP 5xx rate. Integrate incident mgmt for precision.',
        },
        mttr: {
          hours: mttrHours,
          level: mttrLevel,
          note: 'Approximation: Prometheus ALERTS firing duration ÷ distinct alert count (30 d, 15 s scrape assumed). Improve with GoAlert/ADR-0061 ph.3.',
        },
      },
      recentDeployments,
      sources: {
        git: gitAvailable,
        prometheus: prometheusAvailable,
      },
      collectedAt: dora?.collectedAt ?? new Date().toISOString(),
    }, { headers: { 'Cache-Control': 'no-store' } })
  } finally {
    clearTimeout(timer)
  }
}
