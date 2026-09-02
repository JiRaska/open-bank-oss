// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0210 D2 names the risk this file exists for: bronze_events is keyed by
// (aggregate_type, aggregate_id), and transaction events do NOT carry partyId — only accountId. So
// a party's transactions are reached through the accounts that party owns, and if that resolution
// is ever widened the Customer 360 shows ANOTHER CUSTOMER'S account. The ADR's Negative consequence
// says it needs "a test asserting isolation, not just assembly". This is that test.
//
// It asserts on the SQL the route builds, because that is where isolation is decided — a test that
// only checked the assembled output against a hand-written row set would pass against a query that
// leaks, since the fixture would simply not contain the other party's rows.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'
import { auth } from '@/auth'

const ROUTE = path.join(process.cwd(), 'src/app/api/customer-360/[partyId]/route.ts')
const PARTY = '11111111-1111-1111-1111-111111111111'
const OTHER = '22222222-2222-2222-2222-222222222222'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

/** Captures the SQL the route sends, so isolation can be asserted on the query itself. */
function stubClickHouse(rows: Record<string, unknown>[]) {
  const seen: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
    seen.push(String(init?.body ?? ''))
    return { ok: true, json: async () => ({ data: rows }) } as Response
  }))
  return seen
}

beforeEach(() => {
  vi.unstubAllGlobals()
  vi.mocked(auth).mockResolvedValue({ user: { name: 'op', roles: ['ROLE_COMPLIANCE'] } } as never)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Customer 360 isolation (ADR-0210 D2)', () => {
  it('rejects an unauthenticated direct BFF call before querying ClickHouse', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/customer-360/[partyId]/route')

    const res = await GET({} as never, { params: Promise.resolve({ partyId: PARTY }) })

    expect(res.status).toBe(401)
    expect((await res.json()).error).toBe('unauthorized')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects a session without compliance:view before querying ClickHouse', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { roles: ['ROLE_OPERATOR'] } } as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/customer-360/[partyId]/route')

    const res = await GET({} as never, { params: Promise.resolve({ partyId: PARTY }) })

    expect(res.status).toBe(403)
    expect((await res.json()).error).toBe('forbidden')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('bounds the ClickHouse read and degrades a timeout without leaking a 5xx', async () => {
    const signal = new AbortController().signal
    const timeout = vi.spyOn(AbortSignal, 'timeout').mockReturnValue(signal)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('timed out', 'TimeoutError')))
    const { GET } = await import('@/app/api/customer-360/[partyId]/route')

    const res = await GET({} as never, { params: Promise.resolve({ partyId: PARTY }) })

    expect(timeout).toHaveBeenCalledWith(8_000)
    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toMatchObject({ available: false, partyId: PARTY })
  })

  it('scopes BOTH resolution arms to the requested party', async () => {
    const seen = stubClickHouse([])
    const { GET } = await import('@/app/api/customer-360/[partyId]/route')
    await GET({} as never, { params: Promise.resolve({ partyId: PARTY }) })

    expect(seen).toHaveLength(1)
    const sql = seen[0]

    // Direct arm: events that carry partyId.
    expect(sql).toContain(`JSONExtractString(payload, 'partyId') = '${PARTY}'`)
    // Indirect arm: transactions and accounts, reached ONLY through this party's accounts.
    expect(sql).toContain('silver_party_accounts')
    expect(sql).toMatch(/upper\(aggregate_type\) IN \('TRANSACTION', 'ACCOUNT'\)/)
    // EVERY reference to the view must be party-scoped — an unscoped one is the leak. Asserted over
    // all occurrences rather than the first, so adding a second (unscoped) join goes red.
    const refs = sql.split('silver_party_accounts').slice(1)
    expect(refs.length).toBeGreaterThan(0)
    for (const after of refs) {
      expect(after.slice(0, 80)).toMatch(new RegExp(`WHERE party_id = '${PARTY}'`))
    }
    // Type comparisons must be case-folded: bronze stores PARTY/ACCOUNT uppercase, and comparing
    // against lowercase literals matched nothing while looking like "this party has no accounts".
    expect(sql).not.toMatch(/aggregate_type = '[a-z]+'/)
    // And no other party may appear anywhere in the statement.
    expect(sql).not.toContain(OTHER)
  })

  // ADR-0210 D2 says the account→party mapping "materialises as a ClickHouse view alongside the
  // existing silver views" and is "the one thing this ADR adds to the schema". It shipped as an
  // inline CTE in this route instead (#4511) — a definition living in one caller, which is what the
  // ADR rejects for the silver reduction and matters more here, because this resolution IS the
  // isolation boundary. Nothing could see that drift: the route was correct, tested and green.
  it('resolves ownership through the shared view, never by re-deriving it (D2)', async () => {
    const seen = stubClickHouse([])
    const { GET } = await import('@/app/api/customer-360/[partyId]/route')
    await GET({} as never, { params: Promise.resolve({ partyId: PARTY }) })
    const sql = seen[0]

    // The route reads the view and does NOT restate the ownership filter — no second definition.
    expect(sql).not.toContain('bronze_events')
    expect(sql).not.toMatch(/aggregate_type\) = 'ACCOUNT'/)
    expect(sql).not.toContain('WITH ')
  })

  it('the D2 view exists in the DDL of record and carries the guards the route relies on', () => {
    // Read across the tree on purpose: the route's correctness now depends on an object defined in
    // another module, and that dependency is exactly what has no compiler behind it.
    const file = readFileSync(
      path.join(process.cwd(), '../openbank-analytics-sink/src/main/resources/clickhouse/V5__party_accounts.sql'),
      'utf-8',
    )
    // Comments are stripped: the file explains itself at length, and asserting over the prose would
    // pass on a statement that says something else — the file quotes `silver_current_state` to say
    // why it does NOT read it.
    const ddl = file.split('\n').filter((l) => !l.trimStart().startsWith('--')).join('\n')
    expect(ddl).toContain('openbank_analytics.silver_party_accounts')
    // Ownership must be resolved from bronze (full history), not silver: an account's LATEST event
    // is typically BALANCE_UPDATED, which carries accountId but no partyId. Verified against the
    // sandbox — silver holds no ACCOUNT row with a partyId at all.
    expect(ddl).toContain('openbank_analytics.bronze_events')
    expect(ddl).not.toContain('silver_current_state')
    // The case fold the route used to carry itself.
    expect(ddl).toMatch(/upper\(aggregate_type\) = 'ACCOUNT'/)
    // A missing key extracts to '', so without this guard every partyId-less ACCOUNT event becomes a
    // row owned by the empty party — and a caller that skips validation joins to all of them.
    expect(ddl).toMatch(/JSONExtractString\(payload, 'partyId'\) != ''/)
    // CREATE OR REPLACE, not IF NOT EXISTS: the latter is a no-op on a warehouse that already has
    // the object, which is how an edit looks applied in git and does nothing in ClickHouse.
    expect(ddl).toContain('CREATE OR REPLACE VIEW')
    // The columns the route selects on.
    expect(ddl).toMatch(/AS party_id/)
    expect(ddl).toMatch(/AS account_id/)
  })

  it('rejects a non-UUID partyId before it reaches ClickHouse (injection boundary)', async () => {
    const seen = stubClickHouse([])
    const { GET } = await import('@/app/api/customer-360/[partyId]/route')

    for (const bad of ["' OR 1=1 --", 'not-a-uuid', '', '../../etc']) {
      const res = await GET({} as never, { params: Promise.resolve({ partyId: bad }) })
      expect(res.status, bad).toBe(400)
    }
    // The route builds raw SQL, so a rejected id must never have produced a query at all.
    expect(seen).toHaveLength(0)
  })

  it('assembles domains, accounts and consents, and reports staleness', async () => {
    stubClickHouse([
      { aggregate_type: 'PARTY', aggregate_id: PARTY, event_type: 'PartyUpdated', occurred_at: '2026-07-20 10:00:00.000', payload: JSON.stringify({ partyId: PARTY, legalName: 'Alice' }) },
      { aggregate_type: 'ACCOUNT', aggregate_id: 'acc-1', event_type: 'AccountOpened', occurred_at: '2026-07-19 10:00:00.000', payload: JSON.stringify({ partyId: PARTY }) },
      { aggregate_type: 'CONSENT', aggregate_id: 'c-1', event_type: 'ConsentGranted', occurred_at: '2026-07-18 10:00:00.000', payload: JSON.stringify({ partyId: PARTY, status: 'ACTIVE', scopes: ['MARKETING_COMMS_EMAIL'] }) },
      { aggregate_type: 'TRANSACTION', aggregate_id: 'tx-1', event_type: 'TransactionCompleted', occurred_at: '2026-07-17 10:00:00.000', payload: JSON.stringify({ accountId: 'acc-1' }) },
    ])
    const { GET } = await import('@/app/api/customer-360/[partyId]/route')
    const res = await GET({} as never, { params: Promise.resolve({ partyId: PARTY }) })
    const body = await res.json()

    expect(body.available).toBe(true)
    expect(body.asOf).toBe('2026-07-20 10:00:00.000') // newest event, not first row processed
    expect(body.accountIds).toEqual(['acc-1'])
    expect(body.consents).toEqual([{ consentId: 'c-1', status: 'ACTIVE', scopes: ['MARKETING_COMMS_EMAIL'] }])
    expect(body.partyState).toMatchObject({ legalName: 'Alice' })
    expect(body.domains.map((d: { aggregateType: string }) => d.aggregateType).sort())
      .toEqual(['account', 'consent', 'party', 'transaction'])
  })

  it('degrades to available:false when ClickHouse is unreachable, never a 5xx', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('ECONNREFUSED') }))
    const { GET } = await import('@/app/api/customer-360/[partyId]/route')
    const res = await GET({} as never, { params: Promise.resolve({ partyId: PARTY }) })

    expect(res.status).toBe(200) // house style: the page shows a calm state, not an HTTP error
    const body = await res.json()
    expect(body.available).toBe(false)
    expect(body.error).toContain('ECONNREFUSED')
  })

  it('never returns a balance or transaction-level amount (ADR-0210 D3 / ADR-0089)', () => {
    // Asserted on the source, because the guarantee is about what the route CANNOT emit, not about
    // what one fixture happened to omit.
    const src = readFileSync(ROUTE, 'utf-8')
    const iface = src.slice(src.indexOf('export interface Customer360'), src.indexOf('async function chQuery'))
    for (const forbidden of ['balance', 'amount', 'currency']) {
      expect(iface.toLowerCase(), forbidden).not.toContain(forbidden)
    }
  })
  // The distinction this asserts was a real defect: a party with no events came back
  // `available: false`, which the page rendered as "the data source contains no records yet" — while
  // the source held events for other parties. An operator cannot tell that copy apart from a broken
  // page, so `available` must mean one thing only: ClickHouse answered.
  it('reports a party with no events as available, not as an empty data source', async () => {
    stubClickHouse([])
    const { GET } = await import('@/app/api/customer-360/[partyId]/route')
    const res = await GET({} as never, { params: Promise.resolve({ partyId: PARTY }) })

    const body = await res.json()
    expect(res.status).toBe(200)
    expect(body.available).toBe(true) // the source answered
    expect(body.domains).toEqual([]) // this party simply has nothing in it
    expect(body.error).toBeUndefined() // and nothing went wrong
  })

  it('still reports an unreachable source as unavailable', async () => {
    // The other side of the same boundary: `available: false` must remain reachable, or the page
    // loses its only signal that ClickHouse is down.
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('ETIMEDOUT') }))
    const { GET } = await import('@/app/api/customer-360/[partyId]/route')
    const res = await GET({} as never, { params: Promise.resolve({ partyId: OTHER }) })

    const body = await res.json()
    expect(body.available).toBe(false)
    expect(body.error).toContain('ETIMEDOUT')
  })
})
