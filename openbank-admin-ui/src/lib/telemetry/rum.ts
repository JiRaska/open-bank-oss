// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { context, trace, type Tracer } from '@opentelemetry/api'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http'
import { resourceFromAttributes } from '@opentelemetry/resources'
import {
  BatchSpanProcessor,
  WebTracerProvider,
  type SpanProcessor,
} from '@opentelemetry/sdk-trace-web'
import {
  ATTR_SERVICE_NAME,
  ATTR_SERVICE_VERSION,
  ATTR_HTTP_ROUTE,
} from '@opentelemetry/semantic-conventions'

/**
 * Browser RUM for the admin console (issue #5735).
 *
 * The "Mobile RUM" Grafana board queries `traces_spanmetrics_calls_total` (Tempo's
 * metrics-generator derives it for every tenant, so anything that lands in Tempo shows up)
 * and needs the ADR-0070 RUM attribute set — `screen.name`, `app.version`, `device.model`
 * — which nothing in this repo has ever emitted. This module emits exactly those.
 *
 * WHY A SAME-ORIGIN RELAY, not a direct export to the public rum-gateway
 * (`https://rum.open-bank.tech/v1/traces`): that gateway is built for the MOBILE app and a
 * browser cannot use it, for three independent reasons, each sufficient on its own —
 *   1. its OTLP receiver has no `cors:` block, so the browser's preflight OPTIONS on a
 *      cross-origin POST carrying `Authorization` + JSON is never answered;
 *   2. its `oidc` extension pins `issuer_url` to the **openbank-customers** realm with
 *      `audience: openbank-rum`; admin-ui staff hold **openbank**-realm tokens, which that
 *      authenticator rejects;
 *   3. this app's own CSP (`src/proxy.ts`) allows `connect-src 'self' <keycloak> <glitchtip>`
 *      only — `rum.open-bank.tech` is not in it.
 * A same-origin POST to `/api/telemetry/traces` clears all three: it is `'self'` under the
 * CSP, it is already gated by the existing next-auth middleware, and the route handler —
 * running server-side, where the pod already reaches `*.observability.svc` — forwards to the
 * in-cluster collector. That also keeps the collector endpoint a RUNTIME env var
 * (`OTEL_EXPORTER_OTLP_ENDPOINT`, read server-side) rather than a `NEXT_PUBLIC_*` value
 * baked into the image at build time and unchangeable per environment.
 *
 * PII: a bank operator console's URLs carry customer and case ids, so a span never carries a
 * raw pathname. [toScreenName] reduces every path segment that is not a fixed route word to
 * a `:id` placeholder, and the query string is dropped entirely — same posture as the
 * GlitchTip `scrub` next door.
 */

/** Same-origin relay path; the browser never learns the collector's address. */
export const RUM_INGEST_PATH = '/api/telemetry/traces'

export const RUM_SERVICE_NAME = 'openbank-admin-ui'

/** Attribute keys, spelled to match the rum-gateway redaction allow-list / ADR-0070. */
export const ATTR_SCREEN_NAME = 'screen.name'
export const ATTR_APP_VERSION = 'app.version'
export const ATTR_DEVICE_MODEL = 'device.model'
export const ATTR_OS_TYPE = 'os.type'
export const ATTR_OS_VERSION = 'os.version'

/**
 * A segment is masked unless it reads as a fixed route word. Real routes here include
 * `customer-360` and `day-end`, so digits alone cannot be the test — the discriminator is the
 * SHAPE of an identifier: a leading non-letter (uuids, numeric ids), a long run of digits, a
 * lot of digits in total, or an implausibly long segment. Deliberately conservative: masking
 * a real route word only merges two panel rows, whereas leaking a customer id into a span
 * attribute is a PII incident.
 */
export function looksLikeIdentifier(segment: string): boolean {
  if (!/^[a-z][a-z0-9-]*$/.test(segment)) return true // uuid, digits-first, mixed case, %-encoded
  if (segment.length > 24) return true
  if (/\d{4}/.test(segment)) return true // an id-length digit run; `customer-360` has three
  if ((segment.match(/\d/g) ?? []).length > 4) return true
  return false
}

/** Reduce a browser pathname to a low-cardinality screen name (no ids, no query string). */
export function toScreenName(pathname: string): string {
  const clean = pathname.split('?')[0].split('#')[0]
  const segments = clean.split('/').filter(Boolean)
  if (segments.length === 0) return '/'
  return '/' + segments.map((s) => (looksLikeIdentifier(s) ? ':id' : s)).join('/')
}

/**
 * Coarse device model from the user-agent. Deliberately a small closed set: the panel groups
 * by it, so an unbounded string would blow up cardinality (see prometheusrule-rum-cardinality),
 * and a full UA string is a fingerprinting vector we have no use for.
 */
export function toDeviceModel(userAgent: string): string {
  if (/iPad/i.test(userAgent)) return 'ipad'
  if (/iPhone/i.test(userAgent)) return 'iphone'
  if (/Android/i.test(userAgent)) return /Mobile/i.test(userAgent) ? 'android-phone' : 'android-tablet'
  if (/Macintosh|Mac OS X/i.test(userAgent)) return 'desktop-mac'
  if (/Windows/i.test(userAgent)) return 'desktop-windows'
  if (/Linux|X11/i.test(userAgent)) return 'desktop-linux'
  return 'unknown'
}

/** Coarse OS family, matching the `os.type` key the audit cronjob counts. */
export function toOsType(userAgent: string): string {
  if (/iPhone|iPad|iPod/i.test(userAgent)) return 'ios'
  if (/Android/i.test(userAgent)) return 'android'
  if (/Macintosh|Mac OS X/i.test(userAgent)) return 'darwin'
  if (/Windows/i.test(userAgent)) return 'windows'
  if (/Linux|X11/i.test(userAgent)) return 'linux'
  return 'unknown'
}

export interface RumResourceInput {
  userAgent: string
  /** Build version, inlined at build time — it identifies the bundle the browser is running. */
  appVersion: string
}

/** The resource attributes every admin-ui span carries. Exported so a test can assert them. */
export function buildRumResourceAttributes(input: RumResourceInput): Record<string, string> {
  return {
    [ATTR_SERVICE_NAME]: RUM_SERVICE_NAME,
    [ATTR_SERVICE_VERSION]: input.appVersion,
    [ATTR_APP_VERSION]: input.appVersion,
    [ATTR_DEVICE_MODEL]: toDeviceModel(input.userAgent),
    [ATTR_OS_TYPE]: toOsType(input.userAgent),
    [ATTR_OS_VERSION]: toOsVersion(input.userAgent),
  }
}

/** Best-effort major OS version; `unknown` rather than a guess. */
export function toOsVersion(userAgent: string): string {
  const m =
    /(?:Windows NT|Android|CPU(?: iPhone)? OS|Mac OS X) ([0-9]+(?:[._][0-9]+)?)/i.exec(userAgent)
  return m ? m[1].replace(/_/g, '.') : 'unknown'
}

let provider: WebTracerProvider | undefined

/**
 * Build the provider. `spanProcessor` is injectable so a test can attach an in-memory
 * exporter and assert the attributes on a span that was actually EXPORTED — asserting that a
 * tracer "was configured" would prove nothing about what reaches the collector.
 */
export function createRumProvider(
  input: RumResourceInput,
  spanProcessor?: SpanProcessor,
): WebTracerProvider {
  const processor =
    spanProcessor ??
    new BatchSpanProcessor(
      new OTLPTraceExporter({ url: RUM_INGEST_PATH }),
      // Flush often enough that a screen view is not lost when the operator navigates away,
      // but batched enough not to POST per span.
      { scheduledDelayMillis: 5_000, maxExportBatchSize: 64 },
    )
  return new WebTracerProvider({
    resource: resourceFromAttributes(buildRumResourceAttributes(input)),
    spanProcessors: [processor],
  })
}

/** Registers the provider globally. Idempotent — a second call is a no-op. */
export function initRum(input: RumResourceInput, spanProcessor?: SpanProcessor): WebTracerProvider {
  if (provider) return provider
  provider = createRumProvider(input, spanProcessor)
  provider.register()
  return provider
}

/** Test seam: forget the registered provider. */
export function resetRumForTests(): void {
  provider = undefined
}

function rumTracer(): Tracer {
  return trace.getTracer(RUM_SERVICE_NAME)
}

/**
 * Record one screen view. The span NAME is the screen, because the board's headline panels
 * are `sum by (span_name) (rate(traces_spanmetrics_calls_total{...}))` — Tempo's
 * metrics-generator dimensions on span name, not on an arbitrary attribute, so a
 * `screen.name` attribute ALONE would leave "Top screens" empty. Both are set: the attribute
 * is what the rum-attribute-audit cronjob counts and what the Tempo trace query filters on.
 */
export function recordScreenView(pathname: string, tracer: Tracer = rumTracer()): void {
  const screen = toScreenName(pathname)
  const span = tracer.startSpan(`screen.${screen}`, {
    attributes: {
      [ATTR_SCREEN_NAME]: screen,
      [ATTR_HTTP_ROUTE]: screen,
    },
  })
  context.with(trace.setSpan(context.active(), span), () => {
    span.end()
  })
}
