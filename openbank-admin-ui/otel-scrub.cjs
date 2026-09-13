// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * PII scrubbing and path filtering for the admin console's server-side spans.
 *
 * Plain CommonJS on purpose, and at the package root rather than under `src/`, because it has
 * two consumers that cannot share a TypeScript module: `otel-bootstrap.cjs`, which Node loads
 * via `--require` before any application code exists, and the Next.js build, which type-checks
 * and tests it. Duplicating the rules into the bootstrap instead would let the exported spans
 * and the tested behaviour drift apart with nothing to notice.
 */

/**
 * Paths that must not become spans. Next.js serves its own build output from `/_next/`, and a
 * console page pulls dozens of chunks per navigation — tracing them would bury the handful of
 * spans that describe what the operator actually did, and inflate span-metrics cardinality with
 * content-hashed filenames. Health probes are excluded for the same reason: kubelet hits them
 * every few seconds forever and they say nothing an availability alert does not already say.
 */
const UNTRACED_PATH = /^\/(_next\/|favicon\.ico|robots\.txt|healthz$|api\/health$)/

/**
 * Span attributes that can carry a query string, on both the current and the legacy semconv
 * names. `http.target` and `url.query` are the two that made this a real leak rather than a
 * theoretical one: measured 2026-08-21 against the standalone build, a request to
 * `/privacy?token=SECRET123` exported `http.target = "/privacy?token=SECRET123"` and
 * `url.query = "token=SECRET123"` verbatim. An earlier version of this scrub covered only
 * `url.full`/`http.url` — neither of which the inbound HTTP instrumentation sets — so it was a
 * scrub that ran, reported nothing to do, and let the token through.
 */
const URL_ATTRS = ['url.full', 'http.url', 'http.target']

/** Attributes whose entire value IS the query string, and which are therefore dropped. */
const QUERY_ATTRS = ['url.query']

/**
 * Drop the query string from a URL or a request target, keeping scheme/host/path.
 *
 * Returns the input unchanged if it does not parse — a value this function cannot understand is
 * a value it must not half-rewrite into something that looks scrubbed and is not.
 */
function stripQuery(value) {
  try {
    const u = new URL(value)
    u.search = ''
    u.hash = ''
    return u.toString()
  } catch {
    // Relative target (`/privacy?token=…`) or malformed: cut at the first `?` or `#` rather
    // than guess a base.
    const cut = [value.indexOf('?'), value.indexOf('#')].filter((i) => i !== -1)
    return cut.length ? value.slice(0, Math.min(...cut)) : value
  }
}

/**
 * Scrubs a plain attribute bag in place. This is the core: it takes the attributes rather than a
 * span so it can be applied at export time, which is the only place that sees EVERY span.
 *
 * Per-instrumentation hooks (`applyCustomAttributesOnSpan`, `requestHook`) are not sufficient and
 * were measured not to be: with those hooks alone a request to `/privacy?token=SECRET123` still
 * exported the token, because Next.js emits its own `BaseServer.handleRequest` span through the
 * OpenTelemetry API directly and no instrumentation hook of ours ever touches it.
 */
function scrubAttributes(attrs) {
  if (!attrs) return
  for (const key of URL_ATTRS) {
    const v = attrs[key]
    if (typeof v === 'string' && v !== '') attrs[key] = stripQuery(v)
  }
  for (const key of QUERY_ATTRS) {
    if (typeof attrs[key] === 'string' && attrs[key] !== '') attrs[key] = ''
  }
}

/** Applies [scrubAttributes] to every span in an export batch, in place. */
function scrubSpans(spans) {
  for (const span of spans || []) scrubAttributes(span && span.attributes)
  return spans
}

/**
 * Span-object form, kept for the unit test's stand-in and for any caller holding a live Span.
 * Routes through `setAttribute` because a live span's attributes must not be mutated directly.
 */
function scrubSpanUrls(span) {
  const attrs = span && span.attributes
  if (!attrs) return
  for (const key of URL_ATTRS) {
    const v = attrs[key]
    if (typeof v === 'string' && v !== '') span.setAttribute(key, stripQuery(v))
  }
  for (const key of QUERY_ATTRS) {
    if (typeof attrs[key] === 'string' && attrs[key] !== '') span.setAttribute(key, '')
  }
}

module.exports = {
  UNTRACED_PATH,
  URL_ATTRS,
  QUERY_ATTRS,
  stripQuery,
  scrubAttributes,
  scrubSpans,
  scrubSpanUrls,
}
