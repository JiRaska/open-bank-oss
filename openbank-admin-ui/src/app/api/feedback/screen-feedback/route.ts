// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Screen-feedback BFF (ADR-0192). Reads the ClickHouse gold marts from analytics-sink
// V3__screen_feedback.sql / V4__screen_feedback_context.sql and returns one payload for the admin
// "Zpětná vazba k obrazovkám" board: which screens generate reports, the newest reports themselves,
// and the OS/theme/locale combinations behind them.
//
// Shaped exactly like /api/onboarding/funnel-analytics: session-gated, ClickHouse over its HTTP
// interface (no npm client in this project), and it ALWAYS 200s with a typed body — `available:
// false` when ClickHouse is unreachable or empty, so the page degrades to a calm state instead of
// surfacing a raw HTTP error.
//
// Privacy (ADR-0192): a feedback comment is personal data and a screenshot doubly so. This route
// therefore returns the comment ONLY in the recent-reports list an operator explicitly opens, never
// in aggregates, and returns the screenshot object KEY rather than the image or a signed URL —
// fetching the image stays a deliberate, separately-authorised act. party_id is not projected at
// all: it is the key an erasure request uses, not something a product board needs.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

const CLICKHOUSE_URL = process.env.CLICKHOUSE_URL || 'http://localhost:8123'
const CLICKHOUSE_USER = process.env.CLICKHOUSE_USER
const CLICKHOUSE_PASSWORD = process.env.CLICKHOUSE_PASSWORD
const DB = 'openbank_analytics'

/** Cap on the recent-reports list: a board, not an export tool. */
const RECENT_LIMIT = 50

// ── Types returned to the client ────────────────────────────────────────────

interface ScreenRow {
  screenId: string
  bugs: number
  ideas: number
  confusing: number
  total: number
  withScreenshot: number
  lastSeen: string
}
interface RecentRow {
  reference: string
  occurredAt: string
  screenId: string
  category: string
  comment: string
  platform: string
  appVersion: string
  osVersion: string
  locale: string
  theme: string
  screenshotKey: string
  screenshotStatus: string
}
interface ContextRow {
  screenId: string
  platform: string
  osVersion: string
  theme: string
  locale: string
  bugs: number
  submissions: number
}

export interface ScreenFeedbackBoard {
  available: boolean
  from: string
  to: string
  screens: ScreenRow[]
  recent: RecentRow[]
  context: ContextRow[]
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
const str = (v: unknown) => (v == null ? '' : String(v))

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
function isoDay(d: Date): string {
  return d.toISOString().slice(0, 10)
}
function safeDate(raw: string | null, fallback: string): string {
  return raw && DATE_RE.test(raw) ? raw : fallback
}

// ── Route ───────────────────────────────────────────────────────────────────

/**
 * Comments and screenshot keys are personal data (ADR-0192), and this route talks to ClickHouse
 * directly — there is no downstream service @RolesAllowed to fall back on, so the check has to
 * live here as well as in the middleware guard on /feedback (which does not cover a direct
 * fetch of this path).
 */
const ALLOWED_ROLES = ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_COMPLIANCE']

export async function GET(req: NextRequest) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }
  const roles: string[] = session.user.roles ?? []
  if (!roles.some(r => ALLOWED_ROLES.includes(r))) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 })
  }

  const today = new Date()
  const thirtyAgo = new Date(today.getTime() - 30 * 24 * 60 * 60 * 1000)
  const to = safeDate(req.nextUrl.searchParams.get('to'), isoDay(today))
  const from = safeDate(req.nextUrl.searchParams.get('from'), isoDay(thirtyAgo))

  // `from`/`to` are regex-validated to YYYY-MM-DD above, so this interpolation is injection-safe.
  const range = `day BETWEEN toDate('${from}') AND toDate('${to}')`

  const empty: ScreenFeedbackBoard = {
    available: false, from, to, screens: [], recent: [], context: [],
  }

  try {
    const [screenRows, recentRows, contextRows] = await Promise.all([
      // Which screens hurt. Categories are pivoted into columns so the board can rank by bugs
      // while still showing the whole picture per screen.
      chQuery(`
        SELECT screen_id,
               countIf(category = 'BUG')        AS bugs,
               countIf(category = 'IDEA')       AS ideas,
               countIf(category = 'CONFUSING')  AS confusing,
               count()                          AS total,
               countIf(screenshot_key != '')    AS with_screenshot,
               max(occurred_at)                 AS last_seen
        FROM ${DB}.gold_screen_feedback
        WHERE ${range}
        GROUP BY screen_id
        ORDER BY bugs DESC, total DESC
        LIMIT 100`),
      // The reports themselves, newest first. The only place a comment is returned.
      chQuery(`
        SELECT reference, occurred_at, screen_id, category, comment,
               platform, app_version, os_version, locale, theme,
               screenshot_key, screenshot_status
        FROM ${DB}.gold_screen_feedback
        WHERE ${range}
        ORDER BY occurred_at DESC
        LIMIT ${RECENT_LIMIT}`),
      // Rendering context: a fault confined to one OS/theme/locale combination is a rendering
      // regression, not a product problem — that distinction is the whole point of ADR-0192's
      // context fields.
      chQuery(`
        SELECT screen_id, platform, os_version, theme, locale, bugs, submissions
        FROM ${DB}.gold_screen_feedback_context
        ORDER BY bugs DESC, submissions DESC
        LIMIT 50`),
    ])

    const board: ScreenFeedbackBoard = {
      available: true,
      from,
      to,
      screens: screenRows.map((r) => ({
        screenId: str(r.screen_id),
        bugs: num(r.bugs),
        ideas: num(r.ideas),
        confusing: num(r.confusing),
        total: num(r.total),
        withScreenshot: num(r.with_screenshot),
        lastSeen: str(r.last_seen),
      })),
      recent: recentRows.map((r) => ({
        reference: str(r.reference),
        occurredAt: str(r.occurred_at),
        screenId: str(r.screen_id),
        category: str(r.category),
        comment: str(r.comment),
        platform: str(r.platform),
        appVersion: str(r.app_version),
        osVersion: str(r.os_version),
        locale: str(r.locale),
        theme: str(r.theme),
        screenshotKey: str(r.screenshot_key),
        screenshotStatus: str(r.screenshot_status),
      })),
      context: contextRows.map((r) => ({
        screenId: str(r.screen_id),
        platform: str(r.platform),
        osVersion: str(r.os_version),
        theme: str(r.theme),
        locale: str(r.locale),
        bugs: num(r.bugs),
        submissions: num(r.submissions),
      })),
    }
    return NextResponse.json(board)
  } catch (e) {
    // The ClickHouse error body can carry the failing SQL and server detail — logged, never
    // returned. The board only needs to know the mart is not readable right now.
    console.error('screen-feedback board unavailable', e)
    return NextResponse.json({ ...empty, error: 'analytics_unavailable' })
  }
}
