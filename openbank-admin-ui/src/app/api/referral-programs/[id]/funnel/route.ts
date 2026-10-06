// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Governed Campaign Studio read of the privacy-reduced ClickHouse gold view. This path reports
// referral lifecycle observations; it does not post RewardRequested to the ledger.
import { NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

const CLICKHOUSE_URL = process.env.CLICKHOUSE_URL || 'http://localhost:8123'
const CLICKHOUSE_USER = process.env.CLICKHOUSE_USER
const CLICKHOUSE_PASSWORD = process.env.CLICKHOUSE_PASSWORD
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const ALLOWED_ROLES = ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_AUDITOR']

function count(value: unknown): number {
  if (value == null) throw new Error('missing funnel count')
  // ClickHouse quotes UInt64 counts in JSON by default, so decimal strings are legitimate.
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^(0|[1-9]\d*)$/.test(value))) {
    throw new Error('invalid funnel count')
  }
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed < 0) throw new Error('invalid funnel count')
  return parsed
}

export async function GET(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  const roles: string[] = session.user.roles ?? []
  if (!roles.some(role => ALLOWED_ROLES.includes(role))) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }
  const { id } = await params
  if (!UUID_RE.test(id)) return NextResponse.json({ error: 'invalid programId' }, { status: 400 })

  const headers: Record<string, string> = { 'Content-Type': 'text/plain' }
  if (CLICKHOUSE_USER) headers['X-ClickHouse-User'] = CLICKHOUSE_USER
  if (CLICKHOUSE_PASSWORD) headers['X-ClickHouse-Key'] = CLICKHOUSE_PASSWORD
  const query = `
    SELECT program_version, qualified_events, reward_requested_events,
           accepted_outcomes, rejected_outcomes, reversed_outcomes, last_observed_at
    FROM openbank_analytics.gold_referral_lifecycle_funnel
    WHERE program_id = '${id}'
    ORDER BY program_version ASC NULLS LAST
    FORMAT JSON`
  try {
    const response = await fetch(CLICKHOUSE_URL, {
      method: 'POST', headers, body: query, cache: 'no-store', signal: AbortSignal.timeout(8000),
    })
    if (!response.ok) throw new Error('ClickHouse unavailable')
    const body = await response.json() as { data?: Record<string, unknown>[] }
    const items = (body.data ?? []).map(row => {
      const version = row.program_version == null ? null : count(row.program_version)
      if (version === 0) throw new Error('invalid program version')
      return {
        program_version: version,
        qualified_events: count(row.qualified_events),
        reward_requested_events: count(row.reward_requested_events),
        accepted_outcomes: count(row.accepted_outcomes),
        rejected_outcomes: count(row.rejected_outcomes),
        reversed_outcomes: count(row.reversed_outcomes),
        last_observed_at: typeof row.last_observed_at === 'string' &&
          /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(row.last_observed_at)
          ? row.last_observed_at : null,
      }
    })
    return NextResponse.json({ programId: id, items, state: 'ok', ingestionFreshness: 'unknown' })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' }, { status: 502 })
  }
}
