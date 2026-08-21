// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import {
  InMemorySpanExporter,
  SimpleSpanProcessor,
  type ReadableSpan,
} from '@opentelemetry/sdk-trace-web'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createRumProvider,
  recordScreenView,
  resetRumForTests,
  looksLikeIdentifier,
  toDeviceModel,
  toOsType,
  toOsVersion,
  toScreenName,
  RUM_INGEST_PATH,
  RUM_SERVICE_NAME,
} from '@/lib/telemetry/rum'

/**
 * Issue #5735: the Mobile RUM board is empty because nothing ever emitted a span carrying
 * `screen.name` / `app.version` / `device.model`.
 *
 * These assertions run against a REAL WebTracerProvider with an in-memory OTLP exporter, not
 * a mocked SDK: the subject is what an EXPORTED span carries. A test that only asserted "the
 * tracer was configured" would stay green against the exact defect being fixed — remove the
 * attribute block in `recordScreenView` or `buildRumResourceAttributes` and these go red.
 */
const IPHONE_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1'
const MAC_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

function exportOneScreenView(pathname: string, userAgent = MAC_UA, appVersion = '0.173.2') {
  const exporter = new InMemorySpanExporter()
  const provider = createRumProvider(
    { userAgent, appVersion },
    new SimpleSpanProcessor(exporter),
  )
  recordScreenView(pathname, provider.getTracer(RUM_SERVICE_NAME))
  const spans: ReadableSpan[] = exporter.getFinishedSpans()
  return { spans, exporter }
}

afterEach(() => resetRumForTests())

describe('admin-ui RUM instrumentation (#5735)', () => {
  it('exports a span whose RESOURCE carries service.name, app.version and device.model', () => {
    const { spans } = exportOneScreenView('/ledger', IPHONE_UA, '1.2.3')
    expect(spans).toHaveLength(1)
    const attrs = spans[0].resource.attributes
    expect(attrs['service.name']).toBe('openbank-admin-ui')
    expect(attrs['app.version']).toBe('1.2.3')
    expect(attrs['device.model']).toBe('iphone')
    expect(attrs['os.type']).toBe('ios')
    expect(attrs['os.version']).toBe('17.4')
  })

  it('exports a span carrying screen.name — the attribute rum-attribute-audit counted 0 of', () => {
    const { spans } = exportOneScreenView('/payments/sepa')
    expect(spans[0].attributes['screen.name']).toBe('/payments/sepa')
    expect(spans[0].attributes['http.route']).toBe('/payments/sepa')
  })

  it('names the span after the screen, because "Top screens" groups by span_name', () => {
    // The board's two headline panels are
    //   sum by (span_name) (rate(traces_spanmetrics_calls_total{service=~"$app.*"}[5m]))
    // and Tempo's metrics-generator dimensions on span NAME, not on an arbitrary attribute —
    // so a screen.name attribute alone would leave those panels empty.
    const { spans } = exportOneScreenView('/customer-360')
    expect(spans[0].name).toBe('screen./customer-360')
  })

  it('masks identifiers out of the screen name — no customer or case id reaches Tempo', () => {
    expect(toScreenName('/parties/9f3c1e2a-77b1-4d0e-9a55-000000000001/cases/8812')).toBe(
      '/parties/:id/cases/:id',
    )
    expect(toScreenName('/ledger/entries/12345')).toBe('/ledger/entries/:id')
    expect(toScreenName('/dashboard?q=secret')).toBe('/dashboard')
    expect(toScreenName('/')).toBe('/')
    const { spans } = exportOneScreenView('/parties/9f3c1e2a-77b1/detail')
    expect(String(spans[0].attributes['screen.name'])).not.toContain('9f3c1e2a')
    // Real route words with digits survive — `customer-360`/`day-end` are actual routes.
    expect(looksLikeIdentifier('customer-360')).toBe(false)
    expect(looksLikeIdentifier('day-end')).toBe(false)
    expect(looksLikeIdentifier('CZ6508000000192000145399')).toBe(true)
    expect(looksLikeIdentifier('4111111111111111')).toBe(true)
  })

  it('maps device.model / os.type to a small closed set (RUM cardinality budget)', () => {
    expect(toDeviceModel(IPHONE_UA)).toBe('iphone')
    expect(toDeviceModel(MAC_UA)).toBe('desktop-mac')
    expect(toDeviceModel('Mozilla/5.0 (Linux; Android 14; Pixel 8) Mobile Safari')).toBe(
      'android-phone',
    )
    expect(toDeviceModel('curl/8.0')).toBe('unknown')
    expect(toOsType('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toBe('windows')
    expect(toOsVersion('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toBe('10.0')
    expect(toOsVersion('curl/8.0')).toBe('unknown')
  })

  it('exports to the SAME-ORIGIN relay, never to the public rum-gateway', () => {
    // Three independent reasons a direct export cannot work from a browser: the gateway's
    // OTLP receiver declares no `cors:` block, its oidc extension pins the
    // openbank-customers realm with aud=openbank-rum (admin-ui staff hold openbank-realm
    // tokens), and this app's CSP allows connect-src 'self' only.
    expect(RUM_INGEST_PATH).toBe('/api/telemetry/traces')
    expect(RUM_INGEST_PATH.startsWith('/')).toBe(true)
    expect(RUM_INGEST_PATH).not.toContain('rum.open-bank.tech')
  })
})

describe('RUM same-origin relay route (#5735)', () => {
  const OTLP = 'http://otel-collector.observability.svc:4318'

  afterEach(() => {
    delete process.env.OTEL_EXPORTER_OTLP_ENDPOINT
    vi.unstubAllGlobals()
  })

  it('forwards the OTLP body to /v1/traces on the RUNTIME-configured collector', async () => {
    process.env.OTEL_EXPORTER_OTLP_ENDPOINT = `${OTLP}/`
    const calls: Array<[string, RequestInit]> = []
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      calls.push([url, init])
      return new Response(null, { status: 200 })
    })
    const { POST } = await import('@/app/api/telemetry/traces/route')
    const res = await POST(new Request('http://localhost/api/telemetry/traces', {
      method: 'POST',
      body: '{"resourceSpans":[]}',
    }))
    expect(res.status).toBe(204)
    expect(res.headers.get('x-rum-relay')).toBe('forwarded')
    expect(calls[0][0]).toBe(`${OTLP}/v1/traces`)
    expect(calls[0][1].body).toBe('{"resourceSpans":[]}')
  })

  it('is OFF, not broken, when no collector is configured for the environment', async () => {
    const { POST } = await import('@/app/api/telemetry/traces/route')
    const res = await POST(new Request('http://localhost/api/telemetry/traces', {
      method: 'POST',
      body: '{}',
    }))
    // A distinct header, not a shared "success": "RUM is disabled here" must never read as
    // "RUM works here" — that conflation is exactly how the push adapter reported deliveries
    // it never made.
    expect(res.status).toBe(204)
    expect(res.headers.get('x-rum-relay')).toBe('disabled')
  })

  it('never surfaces a collector outage as an operator-visible crash', async () => {
    process.env.OTEL_EXPORTER_OTLP_ENDPOINT = OTLP
    vi.stubGlobal('fetch', async () => { throw new Error('ECONNREFUSED') })
    const { POST } = await import('@/app/api/telemetry/traces/route')
    const res = await POST(new Request('http://localhost/api/telemetry/traces', {
      method: 'POST',
      body: '{}',
    }))
    expect(res.status).toBe(502)
    expect(res.headers.get('x-rum-relay')).toBe('unreachable')
  })
})
