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
import type { Customer360Evidence, DomainSummary } from '@/lib/customer360/evidence'

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

const timestampMillis = (value: string): number =>
  Date.parse(value.replace(' ', 'T') + (value.includes('Z') || /[+-]\d\d:\d\d$/.test(value) ? '' : 'Z'))

export type Customer360 = Customer360Evidence

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

async function chQuery(sql: string): Promise<unknown[]> {
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
  const body = await res.json() as unknown
  if (!isRecord(body) || !Array.isArray(body.data)) throw new Error('Invalid ClickHouse response')
  return body.data
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
    domains: [],
    accountIds: [],
    consents: [],
    excludedCount: 0,
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
    let asOf: string | null = null
    let excludedCount = 0

    for (const rawRow of rows) {
      if (!isRecord(rawRow)) {
        excludedCount += 1
        continue
      }
      const r = rawRow
      const type = typeof r.aggregate_type === 'string' ? r.aggregate_type.trim().toLowerCase() : ''
      const aggregateId = typeof r.aggregate_id === 'string' ? r.aggregate_id.trim() : ''
      const occurredAt = typeof r.occurred_at === 'string' ? r.occurred_at.trim() : ''
      const eventType = typeof r.event_type === 'string' ? r.event_type.trim() : ''
      const parsedAt = timestampMillis(occurredAt)
      let payload: Record<string, unknown>
      try {
        const parsed = JSON.parse(typeof r.payload === 'string' ? r.payload : '') as unknown
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('invalid payload')
        payload = parsed as Record<string, unknown>
      } catch {
        excludedCount += 1
        continue
      }

      if (!type || !aggregateId || !eventType || !occurredAt || Number.isNaN(parsedAt)) {
        excludedCount += 1
        continue
      }

      let consent: Customer360['consents'][number] | null = null
      if (type === 'consent') {
        const status = typeof payload.status === 'string' && payload.status.trim() ? payload.status.trim() : eventType
        const scopes = Array.isArray(payload.scopes) && payload.scopes.every(scope => typeof scope === 'string' && scope.trim())
          ? payload.scopes.map(scope => (scope as string).trim())
          : null
        if (!scopes) {
          excludedCount += 1
          continue
        }
        consent = { consentId: aggregateId, status, scopes }
      }

      if (!asOf || parsedAt > timestampMillis(asOf)) asOf = occurredAt

      const d = byDomain.get(type)
      if (!d) {
        // Rows arrive newest-first, so the first valid row per domain is its latest event.
        byDomain.set(type, { aggregateType: type, events: 1, lastEventType: eventType, lastOccurredAt: occurredAt })
      } else {
        d.events += 1
      }

      if (type === 'account') accountIds.add(aggregateId)
      if (consent) consents.push(consent)
    }

    return NextResponse.json({
      available: true,
      partyId,
      asOf,
      domains: [...byDomain.values()].sort((a, b) => b.events - a.events),
      accountIds: [...accountIds],
      consents,
      excludedCount,
    } satisfies Customer360)
  } catch (e) {
    // The browser needs availability, never warehouse hosts, statuses or query diagnostics.
    // Detailed failures stay server-side; this authenticated response remains topology-neutral.
    console.error('Customer 360 projection query failed', e)
    return NextResponse.json(empty(partyId, 'clickhouse unavailable'))
  }
}
