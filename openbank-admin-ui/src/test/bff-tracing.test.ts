// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

/**
 * Cover for the BFF tracing setup (admin-ui had zero spans and zero scrape targets before it).
 *
 * The assertions are on the two properties that can fail SILENTLY in production:
 *
 *  - the PII scrub, because a span carrying a bearer token or an account number in a query
 *    string exports successfully and looks exactly like a clean one; and
 *  - the gating, because "off because unconfigured" and "on but exporting nowhere" are the
 *    same from the outside, which is the shape this repo keeps finding (a disabled adapter
 *    reporting success).
 */
describe('BFF tracing', () => {
  const ORIGINAL = process.env.OTEL_EXPORTER_OTLP_ENDPOINT

  beforeEach(() => {
    vi.resetModules()
  })
  afterEach(() => {
    if (ORIGINAL === undefined) delete process.env.OTEL_EXPORTER_OTLP_ENDPOINT
    else process.env.OTEL_EXPORTER_OTLP_ENDPOINT = ORIGINAL
  })

  it('strips the query string, which is where tokens and customer ids live', async () => {
    const { stripQuery } = await import('@/lib/telemetry/tracing')

    // The realistic case: an operator opening a customer record through the BFF.
    expect(stripQuery('https://svc/api/v1/parties/7f3?access_token=eyJhbGciOi&partyId=123'))
      .toBe('https://svc/api/v1/parties/7f3')
    // The PATH survives — knowing which route failed is the entire point of the span.
    expect(stripQuery('https://svc/api/v1/approvals/pending')).toBe('https://svc/api/v1/approvals/pending')
    // Fragments too: they are not sent to servers but they are exported on the span.
    expect(stripQuery('https://svc/a?b=1#tok')).toBe('https://svc/a')
  })

  it('does not half-rewrite a value it cannot parse', async () => {
    const { stripQuery } = await import('@/lib/telemetry/tracing')
    // Relative URLs do not parse as absolute; cut at `?` rather than invent a base.
    expect(stripQuery('/api/svc/ledger/accounts?token=abc')).toBe('/api/svc/ledger/accounts')
    // No query, nothing to do — and crucially, unchanged rather than mangled.
    expect(stripQuery('not a url at all')).toBe('not a url at all')
  })

  it('scrubs both the current and the legacy URL attribute names', async () => {
    const { scrubSpanUrls } = await import('@/lib/telemetry/tracing')
    const set = vi.fn()
    const span = {
      attributes: {
        'url.full': 'https://svc/x?tok=1',
        'http.url': 'https://svc/y?tok=2',
        'http.method': 'GET',
      },
      setAttribute: set,
    }
    scrubSpanUrls(span as never)

    // Falsifying detail: a scrub that only knows `url.full` leaves the legacy attribute
    // carrying the token, and the span still exports cleanly. Both must be rewritten.
    expect(set).toHaveBeenCalledWith('url.full', 'https://svc/x')
    expect(set).toHaveBeenCalledWith('http.url', 'https://svc/y')
    // Non-URL attributes are left alone.
    expect(set).not.toHaveBeenCalledWith('http.method', expect.anything())
  })

  it('is a no-op when no OTLP endpoint is configured', async () => {
    delete process.env.OTEL_EXPORTER_OTLP_ENDPOINT
    const { startTracing } = await import('@/lib/telemetry/tracing')

    // Returns false rather than throwing or silently pretending: a developer running
    // `next dev`, and every test in this suite, must export nothing and pay nothing.
    expect(startTracing()).toBe(false)
  })
})
