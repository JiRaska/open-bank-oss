// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

/**
 * The outcome of one attempt to fetch an external feed (ADR-0237 point 2, issue #4743).
 *
 * **Why this is an enum and not a boolean.** A fetch can succeed *as a job* while producing nothing
 * usable, and the three ways that happens are operationally different but indistinguishable under a
 * run/no-run signal:
 *
 * | what happened                                | outcome        | run/no-run heartbeat says |
 * |----------------------------------------------|----------------|---------------------------|
 * | HTTP 200, payload parsed, rows we asked for  | [FETCHED]      | green (correctly)         |
 * | HTTP 200, payload parsed, nothing we asked for| [EMPTY]        | **green** (wrongly)       |
 * | HTTP 404/5xx                                  | [HTTP_ERROR]   | stale, reason unstated    |
 * | HTTP 200, payload not the feed at all         | [PARSE_ERROR]  | stale, reason unstated    |
 * | never got a response                          | [UNREACHABLE]  | stale, reason unstated    |
 * | the job did not run                           | *nothing*      | stale, reason unstated    |
 *
 * The ČNB fixing URL was a 404 for 46 days (#2204) while `FxRevaluationService` kept logging "no
 * movement" — a job that ran successfully while doing nothing. The bottom four rows all look alike
 * to a heartbeat: the age gauge stops advancing and says nothing about *why*. This enum is what
 * turns one stale gauge into a diagnosis.
 *
 * [EMPTY] is the row that has no signal at all today, and it is the one this repo has been bitten by
 * before in a different costume: `PushResult.skipped()` carried `success = true`, so every push in an
 * environment with no APNs credentials was counted as delivered (ADR-0252 phase 0, #4348). **A no-op
 * outcome gets its own value, never a flag shared with success.** The alertable state here is the
 * *success* state — "fetched on schedule, produced nothing, every time" raises no errors anywhere, so
 * no error-rate alert can see it.
 *
 * Pure Kotlin in the domain module for the same reason [WorkflowLivenessMetrics] is: the producer
 * lives in `openbank-libs-runtime` (Micrometer) and the classification belongs to whichever service
 * owns the feed (ADR-0122).
 */
enum class FeedFetchOutcome {
    /** Fetched, parsed, and the payload carried at least one row this consumer wanted. Healthy. */
    FETCHED,

    /**
     * Fetched and parsed, but the payload carried nothing this consumer wanted. Distinct from
     * [PARSE_ERROR]: the feed is alive and well-formed, it just did not contain the data — an
     * upstream that dropped a currency, a consumer configured for rows the feed no longer carries.
     *
     * Not a failure of the run, and not a success of the feed. That is exactly why it needs its own
     * value: an idempotent re-run that re-reads rows already stored is [FETCHED] (the data arrived),
     * while a run that stored nothing *because nothing arrived* is [EMPTY].
     */
    EMPTY,

    /** The upstream answered with a non-2xx status. The 46-day ČNB 404 is this row. */
    HTTP_ERROR,

    /**
     * The upstream answered 2xx but the body was not the feed — an HTML error page, a truncated
     * document, a format change. ČNB serves its own 404 as a 58 KB HTML page, so this and
     * [HTTP_ERROR] are genuinely two different upstream behaviours for one broken URL.
     */
    PARSE_ERROR,

    /**
     * No response was obtained: connect/read timeout, DNS, TLS, or an open circuit breaker.
     *
     * Deliberately not folded into [HTTP_ERROR], mirroring `check-external-feeds.py`'s own triage:
     * unreachable is **not a verdict about the feed, it is the absence of one**. A feed that is
     * unreachable from one network position and fine from another (which `apl.cnb.cz` really is
     * from GitHub's runners) must not be reported as dead.
     */
    UNREACHABLE,
}

/**
 * The single place the external-feed fetch-outcome metric names are written down — the same
 * anti-drift discipline as [WorkflowLivenessMetrics], for the same reason (#2187: a producer and a
 * consumer each spelling a name themselves disagreed for months, and both sides' tests passed).
 *
 * **How this sits beside workflow liveness rather than replacing it.** ADR-0237 point 2 already
 * decided that a feed registers the mechanism-3 primitive under a `feed-`-prefixed workflow tag, so
 * "the scheduler ran" and "the feed delivered" are two independent series and alert independently.
 * That decision is kept verbatim: `DomainMetrics.registerFeedFetch` registers
 * `openbank_workflow_last_success_age_seconds{workflow="feed-<name>"}` and advances it **only** on
 * [FeedFetchOutcome.FETCHED]. The existing `WorkflowLivenessStale` PrometheusRule therefore covers
 * feed freshness with no new rule, no new threshold, and — crucially — the boot-safe registration
 * seed it already has (#4208).
 *
 * What is new is only [FETCH_TOTAL]: the *reason*. The freshness gauge says a feed stopped
 * delivering; the counter says whether it 404s, times out, serves an HTML error page, or cheerfully
 * serves a document with none of the rows we asked for.
 *
 * **Why the reason is a counter that no alert rule reads.** Prometheus retention here is 12h with no
 * long-term store, so `increase(openbank_feed_fetch_total[24h])` on a *daily* feed truncates
 * silently and would answer about a window that does not exist. The alert is therefore the gauge
 * (which carries its state forward with no lookback at all) and the counter is read at triage — by
 * the control-liveness-sentinel and by a human following the alert's annotation. A rule written over
 * this counter would have to be scoped to feeds whose cadence fits inside retention; none does yet.
 */
object FeedFetchMetrics {

    /**
     * Meter name (Micrometer, dotted) of the per-outcome fetch counter, tagged [FEED_TAG] and
     * [OUTCOME_TAG].
     *
     * All [FeedFetchOutcome] values are registered at 0 when the feed registers, so a feed that has
     * never once failed still publishes `outcome="http_error"` at 0. An absent series and a zero
     * series are not the same claim, and PromQL cannot tell "never happened" from "never
     * instrumented" once a counter is created lazily on first increment.
     */
    const val FETCH_TOTAL: String = "openbank.feed.fetch"

    /** Tag carrying the feed's stable low-cardinality name, e.g. `cnb-daily-fixing`. */
    const val FEED_TAG: String = "feed"

    /** Tag carrying the lowercased [FeedFetchOutcome] name, e.g. `http_error`. */
    const val OUTCOME_TAG: String = "outcome"

    /**
     * PromQL series name of [FETCH_TOTAL].
     *
     * Written as a literal rather than derived from [FETCH_TOTAL], because the derivation is not the
     * dot-to-underscore one [WorkflowLivenessMetrics.promSeriesName] performs: Micrometer's
     * Prometheus convention appends `_total` to a counter and nothing in the meter name says so.
     * A helper that only knew about dots would produce `openbank_feed_fetch`, which matches no
     * series — the #2187 defect with an extra step. `FeedFetchMetricNamingTest` pins this literal
     * against the real `PrometheusMeterRegistry` scrape output rather than against any local helper.
     */
    const val FETCH_TOTAL_SERIES: String = "openbank_feed_fetch_total"

    /**
     * The `workflow` tag value a feed's freshness gauge is registered under — ADR-0237 point 2's
     * `feed-` prefix, written once here so no service spells it.
     */
    fun freshnessWorkflow(feed: String): String = "$FRESHNESS_WORKFLOW_PREFIX$feed"

    /** @see freshnessWorkflow */
    const val FRESHNESS_WORKFLOW_PREFIX: String = "feed-"
}
