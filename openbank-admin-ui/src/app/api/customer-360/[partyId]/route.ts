// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Customer 360 BFF (ADR-0210). Reads openbank_analytics.silver_current_state — the existing
// ClickHouse view that reduces bronze_events to current state per aggregate — filtered to one
// party. There is no crm-service and no second database: ADR-0210 D1.
//
// House style (mirrors /api/onboarding/funnel-analytics, /api/finops/costs): this route ALWAYS 200s
// with a typed body. If ClickHouse is unreachable or has nothing, it returns `available: false` so
// the page degrades to a calm DataUnavailable state instead of surfacing a raw HTTP error.
//
// NON-AUTHORITATIVE BY CONSTRUCTION (ADR-0210 D3 / ADR-0089). Every figure here is derived from an
// event projection, not fetched from the owning service, so this route deliberately does NOT return
// balances, transaction-level rows, KYC document content or any risk score. It returns counts,
// recency and lifecycle state, plus `asOf` — the occurred_at of the newest event it reduced — so a
// caller can see how stale the view is rather than assuming it is live.

import { NextRequest, NextResponse } from 'next/server'
import { requireApiPermission } from '@/lib/auth/api-permission'

export const dynamic = 'force-dynamic'

const CLICKHOUSE_URL = process.env.CLICKHOUSE_URL || 'http://localhost:8123'
const CLICKHOUSE_USER = process.env.CLICKHOUSE_USER
const CLICKHOUSE_PASSWORD = process.env.CLICKHOUSE_PASSWORD
const DB = 'openbank_analytics'
const CLICKHOUSE_TIMEOUT_MS = 8_000

// A partyId reaches ClickHouse inside a SQL string, so it is validated as a UUID before it gets
// anywhere near the query. ClickHouse's HTTP interface takes raw SQL and this route builds it, so
// the format check IS the injection boundary — not a convenience.
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export interface DomainSummary {
  aggregateType: string
  events: number
  lastEventType: string
  lastOccurredAt: string
}

export interface Customer360 {
  available: boolean
  partyId: string
  /** occurred_at of the newest event reduced into this view — ADR-0210 D3's staleness signal. */
  asOf: string | null
  /** Current state of the party aggregate itself, as last projected. */
  partyState: Record<string, unknown> | null
  /** Per-domain event counts and recency. Counts and recency only — never figures (D3). */
  domains: DomainSummary[]
  /** Accounts owned by this party, resolved from account events (ADR-0210 D2). Ids only. */
  accountIds: string[]
  /** Consents, from consent events. Status + scopes only; consent-service stays authoritative. */
  consents: { consentId: string; status: string; scopes: string[] }[]
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

/**
 * Every aggregate belonging to one party.
 *
 * bronze_events is keyed by (aggregate_type, aggregate_id), and only SOME events carry partyId in
 * their payload — party, account, consent and kyc events do; transaction events do NOT (they are
 * keyed by accountId). So the party's transactions are reached through the accounts the party owns,
 * which is ADR-0210 D2's account→party resolution — owned by the `silver_party_accounts` view, not
 * by this route.
 *
 * ISOLATION IS THE LOAD-BEARING PROPERTY, AND IT IS NO LONGER SPELLED OUT HERE. Both arms — the
 * direct one on JSONExtractString(payload,'partyId') and the indirect one through the party's
 * account ids — now live in `silver_party_events` (V12__party_event_profile.sql), which carries the
 * party key on every row. This route filters that view to one party and nothing else. V5 collapsed
 * the account→party resolution to one definition and this route's own comment recorded why; the
 * scoping AROUND that resolution stayed behind in the caller, and this is the same collapse one
 * level up. `customer-360.test.ts` still asserts isolation, now against a single WHERE.
 */
function scopedRowsSql(partyId: string): string {
  // Neither the ownership resolution NOR the scoping around it is restated here. V5's
  // `silver_party_accounts` owns the account→party key; `silver_party_events` (V12) owns which rows
  // belong to a party, applying both arms and de-duplicating a row that satisfies both. The two
  // arms used to be an OR written out in this string, which made this caller a second definition of
  // the isolation boundary — the exact hazard V5's header names, one level up (issues #4511, #8792).
  //
  // Measured against the sandbox warehouse before the swap: the view and this route's former OR
  // agree for all 20 parties, max |delta| 0. Dropping the view's de-duplication guard inflates one
  // party by one event, so the agreement is a property of the guard and not of thin data.
  return `
    SELECT aggregate_type, aggregate_id, event_type, occurred_at, payload
    FROM ${DB}.silver_party_events
    WHERE party_id = '${partyId}'
    ORDER BY occurred_at DESC
    LIMIT 5000
  `
}

function empty(partyId: string, error?: string): Customer360 {
  return {
    available: false,
    partyId,
    asOf: null,
    partyState: null,
    domains: [],
    accountIds: [],
    consents: [],
    ...(error ? { error } : {}),
  }
}

export async function GET(_req: NextRequest, ctx: { params: Promise<{ partyId: string }> }) {
  const access = await requireApiPermission('compliance:view')
  if (!access.ok) {
    return NextResponse.json(empty('', access.error), { status: access.status })
  }

  const { partyId } = await ctx.params
  if (!UUID_RE.test(partyId)) {
    return NextResponse.json(empty(partyId, 'partyId must be a UUID'), { status: 400 })
  }

  try {
    const rows = await chQuery(scopedRowsSql(partyId))
    // A query that succeeded and matched nothing means THIS PARTY has no projected events — it does
    // NOT mean the data source is empty. Those are different facts and the page renders different
    // copy for each, so `available` stays true: ClickHouse answered. Returning false here read as
    // "the source contains no records yet" while the source held events for other parties, which is
    // the shape an operator cannot tell apart from a broken page.
    if (rows.length === 0) return NextResponse.json({ ...empty(partyId), available: true })

    const byDomain = new Map<string, DomainSummary>()
    const accountIds = new Set<string>()
    const consents: Customer360['consents'] = []
    let partyState: Record<string, unknown> | null = null
    let asOf: string | null = null

    for (const r of rows) {
      const type = String(r.aggregate_type ?? 'unknown').toLowerCase()
      const occurredAt = String(r.occurred_at ?? '')
      const eventType = String(r.event_type ?? '')

      if (!asOf || occurredAt > asOf) asOf = occurredAt

      const d = byDomain.get(type)
      if (!d) {
        // Rows arrive newest-first, so the first row per domain IS its latest event.
        byDomain.set(type, { aggregateType: type, events: 1, lastEventType: eventType, lastOccurredAt: occurredAt })
      } else {
        d.events += 1
      }

      let payload: Record<string, unknown> = {}
      try {
        payload = JSON.parse(String(r.payload ?? '{}')) as Record<string, unknown>
      } catch {
        continue // a malformed payload must not take down the whole view
      }

      if (type === 'account') accountIds.add(String(r.aggregate_id))
      if (type === 'party' && !partyState) partyState = payload
      if (type === 'consent') {
        consents.push({
          consentId: String(r.aggregate_id),
          status: String(payload.status ?? eventType),
          scopes: Array.isArray(payload.scopes) ? payload.scopes.map(String) : [],
        })
      }
    }

    return NextResponse.json({
      available: true,
      partyId,
      asOf,
      partyState,
      domains: [...byDomain.values()].sort((a, b) => b.events - a.events),
      accountIds: [...accountIds],
      consents,
    } satisfies Customer360)
  } catch (e) {
    return NextResponse.json(empty(partyId, e instanceof Error ? e.message : 'clickhouse unreachable'))
  }
}
