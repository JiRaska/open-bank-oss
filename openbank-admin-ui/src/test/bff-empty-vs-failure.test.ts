// SPDX-License-Identifier: Apache-2.0

// #7943 — BFF routes that turn an upstream FAILURE into an empty collection.
//
// Each of these routes probes Prometheus `/-/ready` first, and correctly reports `available: false`
// when that probe fails. The defect is one layer in: a Prometheus that IS ready but answers 500,
// times out, or returns `status: "error"` had every individual query swallow that into `{}` / `[]`
// / `null`. The response then read `available: true` with an empty result — a confident claim that
// the fleet has no services, no namespaces, or that Temporal is not deployed.
//
// PR #7945 set the precedent on fx/rates: return a discriminated result and surface the reason.
//
// THE CONTROL is the second test in every pair: a query that SUCCEEDS and genuinely returns no
// samples must report `error: null`. Without it, `error` could simply track `rows.length === 0`
// and these guards would pass while measuring nothing.

import { afterEach, describe, expect, it, vi } from 'vitest'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const ready = () => new Response('Prometheus Server is Ready.', { status: 200 })

/** Prometheus answers `/-/ready`, then fails every actual query. */
const readyButQueriesFail = () =>
  vi.fn(async (input: RequestInfo | URL) =>
    String(input).includes('/-/ready') ? ready() : json({ status: 'error', error: 'boom' }, 500))

/** Prometheus is healthy and every query legitimately returns no samples. */
const readyAndGenuinelyEmpty = () =>
  vi.fn(async (input: RequestInfo | URL) =>
    String(input).includes('/-/ready') ? ready() : json({ status: 'success', data: { result: [] } }))

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.resetModules() })

describe('finops/resources reports a failed read as a failure (#7943)', () => {
  it('names the failure instead of rendering an empty fleet', async () => {
    vi.stubGlobal('fetch', readyButQueriesFail())
    const { GET } = await import('@/app/api/finops/resources/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(true)      // Prometheus really is up — that part was never wrong
    expect(body.services).toHaveLength(0)
    expect(body.error).toBeTruthy()        // ...but the emptiness is NOT a fact about the fleet
    expect(body.degraded).toBe(true)
  })

  // CONTROL
  it('reports NO error when the queries succeed and the fleet is genuinely empty', async () => {
    vi.stubGlobal('fetch', readyAndGenuinelyEmpty())
    const { GET } = await import('@/app/api/finops/resources/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(true)
    expect(body.services).toHaveLength(0)
    expect(body.error).toBeNull()
    expect(body.degraded).toBe(false)
  })
})

describe('finops/right-sizing reports a failed read as a failure (#7943)', () => {
  it('names the failure instead of rendering a perfectly-sized fleet', async () => {
    vi.stubGlobal('fetch', readyButQueriesFail())
    const { GET } = await import('@/app/api/finops/right-sizing/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(true)
    expect(body.services).toHaveLength(0)
    expect(body.error).toBeTruthy()
    expect(body.degraded).toBe(true)
    // A failed VPA query has no rows either — it must not be reported as "VPA has no data".
    expect(body.vpaHasData).toBe(false)
  })

  // CONTROL
  it('reports NO error when the queries succeed and there is genuinely nothing to size', async () => {
    vi.stubGlobal('fetch', readyAndGenuinelyEmpty())
    const { GET } = await import('@/app/api/finops/right-sizing/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(true)
    expect(body.services).toHaveLength(0)
    expect(body.error).toBeNull()
    expect(body.degraded).toBe(false)
  })
})

describe('temporal/status does not report an outage as "not deployed" (#7943)', () => {
  it('withholds the deployed verdict when the query failed', async () => {
    vi.stubGlobal('fetch', readyButQueriesFail())
    const { GET } = await import('@/app/api/temporal/status/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(true)
    // The whole defect: `false` here is rendered as "Temporal is not deployed in this environment".
    expect(body.temporalDeployed).not.toBe(false)
    expect(body.error).toBeTruthy()
    expect(body.degraded).toBe(true)
  })

  // CONTROL. `count(temporal_restarts) or vector(0)` genuinely resolving to 0 IS a real answer:
  // Temporal is not scraped. That must stay `false` with no error, or the fix is meaningless.
  it('DOES report not-deployed when the query succeeds and Temporal is absent', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
      String(input).includes('/-/ready')
        ? ready()
        : json({ status: 'success', data: { result: [{ metric: {}, value: [0, '0'] }] } })))
    const { GET } = await import('@/app/api/temporal/status/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(true)
    expect(body.temporalDeployed).toBe(false)
    expect(body.error).toBeNull()
    expect(body.degraded).toBe(false)
  })

  it('still reports prometheus being down as unavailable, without a deployed verdict', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('connection refused') }))
    const { GET } = await import('@/app/api/temporal/status/route')
    const body = await (await GET()).json()

    expect(body.available).toBe(false)
    expect(body.temporalDeployed).not.toBe(false)
    expect(body.error).toBeTruthy()
  })
})
