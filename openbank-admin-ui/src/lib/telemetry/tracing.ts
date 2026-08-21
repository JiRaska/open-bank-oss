// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NodeSDK } from '@opentelemetry/sdk-node'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-proto'
import { UndiciInstrumentation } from '@opentelemetry/instrumentation-undici'
import { resourceFromAttributes } from '@opentelemetry/resources'
import { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION } from '@opentelemetry/semantic-conventions'
import type { Span } from '@opentelemetry/api'

/**
 * Server-side OpenTelemetry tracing for the admin console's BFF (issue #5735 follow-up).
 *
 * ### What this is, and what it deliberately is not
 *
 * This traces the ~40 `/api/*` App-Router route handlers that proxy operator actions to the
 * backend services. It is **not** browser RUM. ADR-0088 D4 rejected Grafana Faro for this
 * surface — "web/JS RUM on an internal operator console (~2 users) is near-zero value" — and
 * that judgement stands and is not reopened here. Nothing in this file runs in a browser.
 *
 * The gap it does close is a different one. Before this, admin-ui had exactly two signals:
 * GlitchTip said an exception was thrown, and the blackbox prober said the pod answered.
 * Measured 2026-08-21: **zero** Prometheus scrape targets in the `admin-ui` namespace and zero
 * spans in Tempo. So when an operator hit a failure on, say, `/api/approvals/pending` — a
 * money-path four-eyes queue — the backend's own trace existed and nothing connected it to the
 * request the human actually made. Every other service in the fleet is traced; the console
 * they are operated from was the blind spot.
 *
 * ### Why this needs no new infrastructure
 *
 * Spans go to the OTLP collector already deployed in-cluster, so they land in Tempo beside the
 * 39 backend services and flow through the existing span-metrics connector — which means
 * `traces_spanmetrics_calls_total{service="openbank-admin-ui"}` starts existing as a
 * side-effect, closing the metrics gap without a second change or a `/metrics` endpoint.
 *
 * This is server-to-collector inside the cluster. It needs none of the hardened public OTLP
 * ingest, CORS or consent machinery that ADR-0088 correctly identifies as the expensive part
 * of *browser* RUM.
 *
 * ### Gating (mirrors ADR-0075 inv. 2, as GlitchTip does with a blank DSN)
 *
 * No `OTEL_EXPORTER_OTLP_ENDPOINT` means no SDK is started at all. A developer running
 * `next dev`, and every test, therefore exports nothing and pays nothing — the feature is off
 * unless an environment deliberately turns it on.
 *
 * ### PII (mirrors ADR-0075 inv. 3)
 *
 * This is a bank operator console: request URLs carry bearer tokens, customer ids and account
 * numbers in path segments and query strings. `[scrub]` strips the query string from every
 * span's URL attributes before export, for the same reason `glitchtip.ts` strips it from every
 * event. The path is kept — knowing WHICH route failed is the whole point — but a path segment
 * that looks like an id is not, on its own, something this layer can safely reason about, so
 * route-level aggregation is left to the span-metrics connector and the raw path is retained
 * only in the trace.
 */

const ENDPOINT = process.env.OTEL_EXPORTER_OTLP_ENDPOINT || ''
const SERVICE_NAME = process.env.OTEL_SERVICE_NAME || 'openbank-admin-ui'
const SERVICE_VERSION = process.env.NEXT_PUBLIC_GLITCHTIP_RELEASE || undefined

/** URL attributes carrying a full URL, on both the current and the legacy semconv names. */
const URL_ATTRS = ['url.full', 'http.url'] as const

/**
 * Drop the query string from a URL, keeping scheme/host/path.
 *
 * Returns the input unchanged if it does not parse — a value this function cannot understand
 * is a value it must not half-rewrite into something that looks scrubbed and is not.
 */
export function stripQuery(value: string): string {
  try {
    const u = new URL(value)
    u.search = ''
    u.hash = ''
    return u.toString()
  } catch {
    // Relative or malformed: cut at the first `?` rather than guess a base.
    const q = value.indexOf('?')
    return q === -1 ? value : value.slice(0, q)
  }
}

/** Applies [scrub] to a span's URL attributes in place. Exported for the unit test. */
export function scrubSpanUrls(span: Span): void {
  // `attributes` is not on the public Span interface (only ReadableSpan), but the SDK's
  // concrete span exposes it and this hook runs inside the SDK, so it is present. Typed
  // narrowly rather than `any` so a future SDK change fails the build instead of silently
  // scrubbing nothing.
  const attrs = (span as unknown as { attributes?: Record<string, unknown> }).attributes
  if (!attrs) return
  for (const key of URL_ATTRS) {
    const v = attrs[key]
    if (typeof v === 'string') span.setAttribute(key, stripQuery(v))
  }
}

let sdk: NodeSDK | undefined

/**
 * Starts tracing. Safe to call more than once; a no-op when unconfigured.
 *
 * Returns whether the SDK was started, so the caller (and the test) can distinguish
 * "deliberately off" from "tried and failed" — the distinction this repo keeps finding it
 * needs, most recently where a disabled adapter reported success.
 */
export function startTracing(): boolean {
  if (sdk || !ENDPOINT) return false

  sdk = new NodeSDK({
    resource: resourceFromAttributes({
      [ATTR_SERVICE_NAME]: SERVICE_NAME,
      ...(SERVICE_VERSION ? { [ATTR_SERVICE_VERSION]: SERVICE_VERSION } : {}),
    }),
    traceExporter: new OTLPTraceExporter(),
    instrumentations: [
      // Next.js 16 issues its outbound BFF calls through undici's fetch. This instrumentation
      // is what injects W3C `traceparent` on the way out, which is the entire point: without
      // it the console would produce its own orphan traces instead of joining the backend's.
      new UndiciInstrumentation({
        requestHook: (span) => scrubSpanUrls(span),
      }),
    ],
  })
  sdk.start()
  return true
}
