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
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

const CLICKHOUSE_URL = process.env.CLICKHOUSE_URL || 'http://localhost:8123'
const CLICKHOUSE_USER = process.env.CLICKHOUSE_USER
const CLICKHOUSE_PASSWORD = process.env.CLICKHOUSE_PASSWORD
const DB = 'openbank_analytics'

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
 * which is ADR-0210 D2's account→party resolution, done here in SQL.
 *
 * ISOLATION IS THE LOAD-BEARING PROPERTY. Both arms filter on this party: the direct arm on
 * JSONExtractString(payload,'partyId'), the indirect arm on aggregate_id IN (that party's account
 * ids). If the indirect arm were ever widened, this route would show another customer's
 * transactions — which is why `customer-360.test.ts` asserts isolation, not just assembly.
 */
function scopedRowsSql(partyId: string): string {
  // `aggregate_type` is UPPERCASE in bronze (PARTY, ACCOUNT, ONBOARDING_FUNNEL — confirmed against
  // the sandbox), so every comparison is folded with upper(). The first version compared against
  // lowercase literals and silently matched nothing on the account arm: a filter that finds no rows
  // is indistinguishable from a party that has no accounts, so nothing failed — it just showed
  // less. Casing is normalised here and again when the rows are grouped.
  //
  // party_accounts reads bronze_events, NOT silver_current_state, and that is deliberate: silver
  // keeps only the LATEST event per aggregate, and an account's latest event is typically
  // BALANCE_UPDATED, whose payload has accountId but no partyId. Resolving ownership needs the
  // event that carried it (account opened), which only the full history has.
  return `
    WITH party_accounts AS (
      SELECT DISTINCT aggregate_id
      FROM ${DB}.bronze_events
      WHERE upper(aggregate_type) = 'ACCOUNT'
        AND JSONExtractString(payload, 'partyId') = '${partyId}'
    )
    SELECT aggregate_type, aggregate_id, event_type, occurred_at, payload
    FROM ${DB}.silver_current_state
    WHERE JSONExtractString(payload, 'partyId') = '${partyId}'
       OR (upper(aggregate_type) = 'TRANSACTION'
           AND aggregate_id IN (SELECT aggregate_id FROM party_accounts))
       OR (upper(aggregate_type) = 'ACCOUNT'
           AND aggregate_id IN (SELECT aggregate_id FROM party_accounts))
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
  const session = await auth()
  if (!session) return NextResponse.json(empty('', 'unauthorized'), { status: 401 })

  const { partyId } = await ctx.params
  if (!UUID_RE.test(partyId)) {
    return NextResponse.json(empty(partyId, 'partyId must be a UUID'), { status: 400 })
  }

  try {
    const rows = await chQuery(scopedRowsSql(partyId))
    if (rows.length === 0) return NextResponse.json(empty(partyId))

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
