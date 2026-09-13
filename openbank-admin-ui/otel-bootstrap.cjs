// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Server-side OpenTelemetry bootstrap for the admin console's BFF (issue #5735 follow-up).
 *
 * ### Why this is a `--require` preload and NOT `instrumentation.ts`
 *
 * Next.js documents `instrumentation.ts` as the place to start an OTel SDK, and for the Next.js
 * native spans that works. For `@opentelemetry/instrumentation-http` it does not: that
 * instrumentation works by monkey-patching `node:http` at require time, and by the moment
 * Next.js calls `register()` the standalone server has already loaded `node:http` and created
 * its listener. The patch then lands on nothing.
 *
 * This failure is silent from every angle you would normally check. Measured 2026-08-21 against
 * the standalone build: the SDK starts (its `start()` frame is in the debug log, resolved out of
 * `node_modules`, so the `serverExternalPackages` fix from #6164 is working), the process is
 * healthy, requests are served — and **zero** spans are exported. `NEXT_OTEL_VERBOSE=1` made no
 * difference. Only preloading the SDK before the application, via
 * `NODE_OPTIONS=--require ./otel-bootstrap.cjs`, produced spans; the same build without the
 * preload exported nothing, which is the falsification that makes this file load-bearing rather
 * than decorative.
 *
 * ### What it traces, and what it deliberately is not
 *
 * Inbound requests to the console, plus the outbound BFF calls those handlers make. It is
 * **not** browser RUM. ADR-0088 D4 rejected Grafana Faro for this surface — "web/JS RUM on an
 * internal operator console (~2 users) is near-zero value" — and that judgement stands and is
 * not reopened here. Nothing in this file runs in a browser.
 *
 * Inbound is what makes the console observable at all. Every route except `/auth`, `/privacy`
 * and `/.well-known/` answers 307 before any handler runs (`src/proxy.ts`, ADR-0080 P0), so an
 * unauthenticated request makes no outbound call and therefore produced no span. Outbound-only
 * tracing left admin-ui invisible in Tempo from any traffic a probe — or the synthetic journey,
 * which only GETs `/` — can generate.
 *
 * ### Why this needs no new infrastructure
 *
 * Spans go to the OTLP collector already deployed in-cluster, so they land in Tempo beside the
 * 39 backend services and flow through the existing span-metrics connector — which means
 * `traces_spanmetrics_calls_total{service="openbank-admin-ui"}` starts existing as a
 * side-effect, closing the metrics gap without a `/metrics` endpoint. It is server-to-collector
 * inside the cluster, needing none of the hardened public OTLP ingest, CORS or consent
 * machinery that ADR-0088 correctly identifies as the expensive part of *browser* RUM.
 *
 * ### Gating (mirrors ADR-0075 inv. 2, as GlitchTip does with a blank DSN)
 *
 * No `OTEL_EXPORTER_OTLP_ENDPOINT` means no SDK is started at all, so a developer running
 * `next dev` and every test export nothing and pay nothing.
 *
 * ### PII (mirrors ADR-0075 inv. 3)
 *
 * See `otel-scrub.cjs`: this is a bank operator console, and request targets carry tokens and
 * customer ids in query strings.
 */

const { UNTRACED_PATH, scrubSpans } = require('./otel-scrub.cjs')

/** OTLP exporter that strips query strings from every span in the batch before sending. */
function scrubbingExporter() {
  const { OTLPTraceExporter } = require('@opentelemetry/exporter-trace-otlp-proto')
  const exporter = new OTLPTraceExporter()
  const send = exporter.export.bind(exporter)
  exporter.export = (spans, resultCallback) => send(scrubSpans(spans), resultCallback)
  return exporter
}

function startTracing() {
  if (!process.env.OTEL_EXPORTER_OTLP_ENDPOINT) return false

  const { NodeSDK } = require('@opentelemetry/sdk-node')
  const { HttpInstrumentation } = require('@opentelemetry/instrumentation-http')
  const { UndiciInstrumentation } = require('@opentelemetry/instrumentation-undici')
  const { resourceFromAttributes } = require('@opentelemetry/resources')
  const {
    ATTR_SERVICE_NAME,
    ATTR_SERVICE_VERSION,
  } = require('@opentelemetry/semantic-conventions')

  const version = process.env.NEXT_PUBLIC_GLITCHTIP_RELEASE

  const sdk = new NodeSDK({
    resource: resourceFromAttributes({
      [ATTR_SERVICE_NAME]: process.env.OTEL_SERVICE_NAME || 'openbank-admin-ui',
      ...(version ? { [ATTR_SERVICE_VERSION]: version } : {}),
    }),
    // Scrubbing happens HERE, wrapping the exporter, and not in per-instrumentation hooks.
    // Measured 2026-08-21: with hooks alone the token still reached the wire, because Next.js
    // emits `BaseServer.handleRequest` through the OpenTelemetry API directly and no hook of
    // ours runs for it. The exporter is the one place that sees every span whatever created it.
    traceExporter: scrubbingExporter(),
    instrumentations: [
      new HttpInstrumentation({
        ignoreIncomingRequestHook: (req) => UNTRACED_PATH.test((req && req.url) || ''),
      }),
      // Next.js 16 issues its outbound BFF calls through undici's fetch. This is what injects
      // W3C `traceparent` on the way out, which is the entire point: without it the console
      // would produce its own orphan traces instead of joining the backend's.
      new UndiciInstrumentation(),
    ],
  })
  sdk.start()
  return true
}

module.exports = { startTracing }

// Preloaded via NODE_OPTIONS, so requiring this file IS the instruction to start.
startTracing()
