// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Governed reporting BFF — ADR-0286 (issue #8943).
//
// The ONLY query surface between the operator's browser and the ClickHouse warehouse. The browser
// names a registry entry and supplies parameters; the SQL is built server-side from validated
// values only (the registry's validators are the injection boundary — the browser never sends SQL).
//
// House style (mirrors /api/customer-360/[partyId], /api/onboarding/funnel-analytics): this route
// ALWAYS 200s a typed body for an authenticated, authorised, well-formed request. If ClickHouse is
// unreachable or errors, it returns `available: false` so the page degrades to a calm
// DataUnavailable state instead of surfacing a raw HTTP error. Denials are the only non-200s:
// 401 unauthenticated, 403 without the entry's permission, 404 unknown report, 400 invalid params —
// a malformed request is a client bug, not a degraded data source.

import { NextRequest, NextResponse } from 'next/server'
import { requireApiPermission } from '@/lib/auth/api-permission'
import { getReport, validateParams, type ReportColumn } from '@/lib/reporting/registry'

export const dynamic = 'force-dynamic'

const CLICKHOUSE_URL = process.env.CLICKHOUSE_URL || 'http://localhost:8123'
const CLICKHOUSE_USER = process.env.CLICKHOUSE_USER
const CLICKHOUSE_PASSWORD = process.env.CLICKHOUSE_PASSWORD
const CLICKHOUSE_TIMEOUT_MS = 8_000

// Every response is capped: a registry report is an operator surface, not an export channel, and
// no risk figure needs more rows than a screen can hold. Entries that can exceed this carry their
// own tighter LIMIT in SQL; this is the backstop.
const MAX_ROWS = 1_000

export interface ReportResult {
  available: boolean
  reportId: string
  titleCs: string
  titleEn: string
  columns: readonly ReportColumn[]
  rows: Record<string, unknown>[]
  /** When the warehouse reduced the events into these rows. null when unavailable. */
  generatedAt: string | null
  rowCount: number
  truncated: boolean
  error?: string
}

async function chQuery(sql: string): Promise<Record<string, unknown>[]> {
  const headers: Record<string, string> = { 'Content-Type': 'text/plain' }
  if (CLICKHOUSE_USER) headers['X-ClickHouse-User'] = CLICKHOUSE_USER
  if (CLICKHOUSE_PASSWORD) headers['X-ClickHouse-Key'] = CLICKHOUSE_PASSWORD
  const res = await fetch(`${CLICKHOUSE_URL}/?default_format=JSON`, {
    method: 'POST',
    headers,
    body: sql,
    cache: 'no-store',
    signal: AbortSignal.timeout(CLICKHOUSE_TIMEOUT_MS),
  })
  if (!res.ok) throw new Error(`ClickHouse ${res.status}`)
  const body = (await res.json()) as { data?: Record<string, unknown>[] }
  return body.data ?? []
}

function empty(reportId: string, error?: string): ReportResult {
  return {
    available: false,
    reportId,
    titleCs: '',
    titleEn: '',
    columns: [],
    rows: [],
    generatedAt: null,
    rowCount: 0,
    truncated: false,
    ...(error ? { error } : {}),
  }
}

export async function GET(req: NextRequest, ctx: { params: Promise<{ queryId: string }> }) {
  const { queryId } = await ctx.params
  const entry = getReport(queryId)
  if (!entry) {
    return NextResponse.json(empty(queryId, 'unknown report'), { status: 404 })
  }

  // The permission is the ENTRY's, not the route's: a registry entry is a new data exposure and
  // its scope is reviewable in the registry file, next to the SQL it guards (ADR-0286).
  const access = await requireApiPermission(entry.permission)
  if (!access.ok) {
    return NextResponse.json(empty(queryId, access.error), { status: access.status })
  }

  const raw: Record<string, string | null> = {}
  for (const param of entry.params) {
    raw[param.name] = req.nextUrl.searchParams.get(param.name)
  }
  const validated = validateParams(entry, raw)
  if (!validated.ok) {
    return NextResponse.json(empty(queryId, `invalid parameter: ${validated.param}`), { status: 400 })
  }

  try {
    const rows = await chQuery(entry.sql(validated.params))
    const truncated = rows.length > MAX_ROWS
    const body: ReportResult = {
      available: true,
      reportId: entry.id,
      titleCs: entry.titleCs,
      titleEn: entry.titleEn,
      columns: entry.columns,
      rows: truncated ? rows.slice(0, MAX_ROWS) : rows,
      generatedAt: new Date().toISOString(),
      rowCount: Math.min(rows.length, MAX_ROWS),
      truncated,
    }
    return NextResponse.json(body, { headers: { 'Cache-Control': 'no-store' } })
  } catch (e) {
    // ClickHouse unreachable / query error → degrade to a calm empty state (never a raw 5xx).
    return NextResponse.json(
      { ...empty(queryId), titleCs: entry.titleCs, titleEn: entry.titleEn, columns: entry.columns, error: e instanceof Error ? e.message : 'clickhouse unreachable' },
      { headers: { 'Cache-Control': 'no-store' } },
    )
  }
}
