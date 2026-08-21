// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

// The scrub rules are plain CommonJS at the package root, not a TS module under src/, because
// `otel-bootstrap.cjs` is loaded by Node via --require before any application code exists and
// cannot import TypeScript. Testing that exact file — rather than a TS copy of it — is the
// point: a copy would let the exported spans and the tested behaviour drift apart.
const scrub = require('../../otel-scrub.cjs') as {
  UNTRACED_PATH: RegExp
  stripQuery: (v: string) => string
  scrubSpans: (spans: unknown[]) => unknown[]
  scrubSpanUrls: (span: unknown) => void
}

/** Minimal stand-in for the SDK's concrete span: reads and writes the same attribute bag. */
function fakeSpan(attributes: Record<string, unknown>) {
  return {
    attributes,
    setAttribute(key: string, value: unknown) {
      this.attributes[key] = value
    },
  }
}

describe('stripQuery', () => {
  it('drops the query and fragment from an absolute URL', () => {
    expect(scrub.stripQuery('https://h/api/x?token=SECRET#frag')).toBe('https://h/api/x')
  })

  it('drops the query from a relative request target', () => {
    // This is the shape `http.target` actually carries; an absolute-URL-only scrub let it through.
    expect(scrub.stripQuery('/privacy?token=SECRET')).toBe('/privacy')
  })

  it('leaves a value with no query untouched', () => {
    expect(scrub.stripQuery('/api/approvals/pending')).toBe('/api/approvals/pending')
  })
})

describe('scrubSpanUrls', () => {
  it('removes a token from every attribute that can carry a query string', () => {
    // Measured against the standalone build on 2026-08-21: a request to
    // `/privacy?token=SECRET123` exported these three attributes verbatim.
    const span = fakeSpan({
      'http.target': '/privacy?token=SECRET123',
      'url.query': 'token=SECRET123',
      'url.full': 'https://admin/privacy?token=SECRET123',
      'http.url': 'https://admin/privacy?token=SECRET123',
      'url.path': '/privacy',
    })

    scrub.scrubSpanUrls(span)

    expect(JSON.stringify(span.attributes)).not.toContain('SECRET123')
    expect(span.attributes['http.target']).toBe('/privacy')
    expect(span.attributes['url.query']).toBe('')
    // The path is deliberately kept — knowing WHICH route failed is the whole point.
    expect(span.attributes['url.path']).toBe('/privacy')
  })

  it('does not throw on a span with no attributes', () => {
    expect(() => scrub.scrubSpanUrls({})).not.toThrow()
  })
})

describe('scrubSpans (the exporter path)', () => {
  it('scrubs a span no instrumentation hook of ours ever touches', () => {
    // This is the case that made exporter-level scrubbing necessary: Next.js emits
    // `BaseServer.handleRequest` through the OpenTelemetry API directly, so with
    // per-instrumentation hooks alone the token reached the wire. Measured 2026-08-21.
    const spans = [
      { name: 'BaseServer.handleRequest', attributes: { 'http.target': '/privacy?token=SECRET123' } },
      { name: 'GET', attributes: { 'url.query': 'token=SECRET123', 'url.path': '/privacy' } },
    ]

    scrub.scrubSpans(spans)

    expect(JSON.stringify(spans)).not.toContain('SECRET123')
    expect(spans[0].attributes['http.target']).toBe('/privacy')
    expect(spans[1].attributes['url.path']).toBe('/privacy')
  })

  it('tolerates an empty batch and a span with no attributes', () => {
    expect(() => scrub.scrubSpans([])).not.toThrow()
    expect(() => scrub.scrubSpans([{}])).not.toThrow()
  })
})

describe('UNTRACED_PATH', () => {
  it('excludes build output and health probes', () => {
    for (const p of ['/_next/static/chunk.js', '/favicon.ico', '/healthz', '/api/health']) {
      expect(scrub.UNTRACED_PATH.test(p), p).toBe(true)
    }
  })

  it('still traces the operator routes this exists to make visible', () => {
    // The other half of the assertion: an over-broad exclusion would silently trace nothing,
    // which is the failure this whole change was made to fix.
    for (const p of ['/api/approvals/pending', '/api/svc/ledger/accounts', '/', '/privacy']) {
      expect(scrub.UNTRACED_PATH.test(p), p).toBe(false)
    }
  })
})

describe('standalone output', () => {
  const modules = join(process.cwd(), '.next', 'standalone', 'node_modules', '@opentelemetry')

  it.runIf(existsSync(modules))(
    'carries the OpenTelemetry packages otel-bootstrap.cjs requires',
    () => {
      // Without these the container crashes on `--require` with MODULE_NOT_FOUND. They are in
      // the image only because src/lib/telemetry/tracing.ts references them; see that file.
      const present = readdirSync(modules)
      for (const pkg of [
        'sdk-node',
        'exporter-trace-otlp-proto',
        'instrumentation-http',
        'instrumentation-undici',
      ]) {
        expect(present, pkg).toContain(pkg)
      }
    },
  )
})
