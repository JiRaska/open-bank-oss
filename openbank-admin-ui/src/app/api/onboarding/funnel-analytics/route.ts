// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Onboarding-funnel analytics BFF (ADR-0069 Phase 2). Reads the ClickHouse gold marts defined in
// openbank-analytics-sink V2__onboarding_funnel.sql and returns a single aggregated payload for the
// admin "Konverze onboardingu" board: the step funnel (viewed/completed/drop-off + median dwell),
// the final-signature outcomes over time, the top signature-failure reasons, and the KYC-method
// split. Session-gated (NextAuth → Keycloak), like every other admin route. ClickHouse has no npm
// client in this project, so we talk to its HTTP interface with fetch; URL + creds stay in env.
//
// House style (mirrors /api/test-results, /api/finops/costs): this route ALWAYS 200s with a typed
// body. If ClickHouse is unreachable or empty, it returns `available: false` so the page degrades to
// a calm DataUnavailable state instead of surfacing a raw HTTP error.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

// ClickHouse HTTP interface. Overridable via env for local/dev; creds are env-only, never hardcoded.
const CLICKHOUSE_URL = process.env.CLICKHOUSE_URL || 'http://localhost:8123'
const CLICKHOUSE_USER = process.env.CLICKHOUSE_USER
const CLICKHOUSE_PASSWORD = process.env.CLICKHOUSE_PASSWORD
const DB = 'openbank_analytics'

// Step order + Czech/English labels shared with the client via the payload.
const STEP_ORDER = ['WELCOME', 'IDENTITY', 'EMAIL', 'AGREEMENT', 'PASSKEY', 'SIGN'] as const

// ── Types returned to the client ────────────────────────────────────────────

interface FunnelStep {
  step: string
  stepOrdinal: number
  viewed: number
  completed: number
  holdAbandons: number
  dropOffPct: number      // (viewed - completed) / viewed * 100
  medianSeconds: number | null
}
interface SignOutcome { day: string; attempts: number; successes: number; failures: number }
interface FailReason { reason: string; failures: number }
interface KycMethod { method: string; sessions: number }

export interface FunnelAnalytics {
  available: boolean
  from: string
  to: string
  steps: FunnelStep[]
  signOutcomes: SignOutcome[]
  failReasons: FailReason[]
  kycMethods: KycMethod[]
  error?: string
}

// ── ClickHouse HTTP helper ──────────────────────────────────────────────────

/** Run one SQL statement over the ClickHouse HTTP interface and return the JSON `data` rows. */
async function chQuery(sql: string): Promise<Record<string, unknown>[]> {
  const headers: Record<string, string> = { 'Content-Type': 'text/plain' }
  if (CLICKHOUSE_USER) headers['X-ClickHouse-User'] = CLICKHOUSE_USER
  if (CLICKHOUSE_PASSWORD) headers['X-ClickHouse-Key'] = CLICKHOUSE_PASSWORD

  const res = await fetch(`${CLICKHOUSE_URL}/?default_format=JSON`, {
    method: 'POST',
    headers,
    body: sql,
    cache: 'no-store',
    signal: AbortSignal.timeout(8000),
  })
  if (!res.ok) {
    const detail = await res.text().catch(() => '')
    throw new Error(`ClickHouse HTTP ${res.status}: ${detail.slice(0, 200)}`)
  }
  const parsed = (await res.json()) as { data?: Record<string, unknown>[] }
  return parsed.data ?? []
}

const num = (v: unknown) => (v == null ? 0 : Number(v)) || 0

// ISO date, guarded — only YYYY-MM-DD is ever interpolated into SQL.
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
function isoDay(d: Date): string {
  return d.toISOString().slice(0, 10)
}
function safeDate(raw: string | null, fallback: string): string {
  return raw && DATE_RE.test(raw) ? raw : fallback
}

// ── Route ───────────────────────────────────────────────────────────────────

export async function GET(req: NextRequest) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }

  const today = new Date()
  const thirtyAgo = new Date(today.getTime() - 30 * 24 * 60 * 60 * 1000)
  const to = safeDate(req.nextUrl.searchParams.get('to'), isoDay(today))
  const from = safeDate(req.nextUrl.searchParams.get('from'), isoDay(thirtyAgo))

  // `from`/`to` are regex-validated to YYYY-MM-DD above, so this interpolation is injection-safe.
  const range = `day BETWEEN toDate('${from}') AND toDate('${to}')`

  const empty: FunnelAnalytics = {
    available: false, from, to, steps: [], signOutcomes: [], failReasons: [], kycMethods: [],
  }

  try {
    const [funnelRows, durationRows, signRows, reasonRows, kycRows] = await Promise.all([
      chQuery(`
        SELECT step,
               any(step_ordinal)      AS step_ordinal,
               sum(sessions_viewed)   AS viewed,
               sum(sessions_completed) AS completed,
               sum(hold_abandons)     AS hold_abandons
        FROM ${DB}.gold_onboarding_funnel_daily
        WHERE ${range}
        GROUP BY step`),
      chQuery(`
        SELECT step, quantile(0.5)(seconds_on_step) AS median_seconds
        FROM ${DB}.gold_onboarding_step_durations
        WHERE ${range}
        GROUP BY step`),
      chQuery(`
        SELECT day,
               sum(attempts)   AS attempts,
               sum(successes)  AS successes,
               sum(failures)   AS failures
        FROM ${DB}.gold_onboarding_sign_outcomes
        WHERE ${range}
        GROUP BY day
        ORDER BY day`),
      chQuery(`
        SELECT reason, sum(failures) AS failures
        FROM ${DB}.gold_onboarding_sign_fail_reasons
        WHERE ${range}
        GROUP BY reason
        ORDER BY failures DESC
        LIMIT 10`),
      chQuery(`
        SELECT kyc_method, sum(sessions) AS sessions
        FROM ${DB}.gold_onboarding_kyc_method_daily
        WHERE ${range}
        GROUP BY kyc_method
        ORDER BY sessions DESC`),
    ])

    const medianByStep = new Map<string, number>(
      durationRows.map(r => [String(r.step), num(r.median_seconds)]),
    )
    const funnelByStep = new Map<string, Record<string, unknown>>(
      funnelRows.map(r => [String(r.step), r]),
    )

    // Walk the canonical step order so the funnel is always complete + ordered, even if a step has
    // no rows yet in the selected range.
    const steps: FunnelStep[] = STEP_ORDER.map((step, i) => {
      const r = funnelByStep.get(step)
      const viewed = num(r?.viewed)
      const completed = num(r?.completed)
      const median = medianByStep.has(step) ? Math.round(medianByStep.get(step)!) : null
      return {
        step,
        stepOrdinal: r ? num(r.step_ordinal) : i + 1,
        viewed,
        completed,
        holdAbandons: num(r?.hold_abandons),
        dropOffPct: viewed > 0 ? ((viewed - completed) / viewed) * 100 : 0,
        medianSeconds: median,
      }
    })

    const signOutcomes: SignOutcome[] = signRows.map(r => ({
      day: String(r.day),
      attempts: num(r.attempts),
      successes: num(r.successes),
      failures: num(r.failures),
    }))
    const failReasons: FailReason[] = reasonRows.map(r => ({
      reason: String(r.reason || 'unknown'),
      failures: num(r.failures),
    }))
    const kycMethods: KycMethod[] = kycRows.map(r => ({
      method: String(r.kyc_method || 'unknown'),
      sessions: num(r.sessions),
    }))

    const hasData =
      steps.some(s => s.viewed > 0 || s.completed > 0) ||
      signOutcomes.length > 0 || kycMethods.length > 0

    const body: FunnelAnalytics = {
      available: hasData, from, to, steps, signOutcomes, failReasons, kycMethods,
    }
    return NextResponse.json(body, { headers: { 'Cache-Control': 'no-store' } })
  } catch (e) {
    // ClickHouse unreachable / query error → degrade to a calm empty state (never a raw 5xx).
    console.error('onboarding funnel-analytics failed:', e)
    return NextResponse.json(
      { ...empty, error: e instanceof Error ? e.message : String(e) },
      { headers: { 'Cache-Control': 'no-store' } },
    )
  }
}
