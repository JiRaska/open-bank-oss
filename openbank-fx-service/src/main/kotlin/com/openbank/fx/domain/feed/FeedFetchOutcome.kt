// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.feed

/**
 * The real outcome of one attempt to fetch and parse an external feed (issue #4743, split from
 * ADR-0237's scheduler-liveness heartbeat).
 *
 * Deliberately its own contract, never folded into "the scheduled job completed without
 * throwing" — that heartbeat and this outcome answer different questions, and conflating them is
 * the exact ČNB incident this repo has already lived through: the fixing URL resolved to a wrong
 * path for 46 days (#2204) while `CnbRateIngestionScheduler`'s own heartbeat (`fx-cnb-ingestion`)
 * kept recording success on every run, because the ingestion's `catch` swallowed the parse
 * failure into one log line and the scheduler never got the chance to fail. A job can run and
 * succeed while the feed underneath it delivers nothing usable — an HTTP 404 completes without
 * throwing, and a 200 with zero parsed rows completes without throwing either, so a heartbeat
 * that only asks "did the job run" cannot tell either of those apart from a real day's fixing.
 *
 * This repo's own precedent for the shape — a "successful no-op" must have its own outcome value,
 * never share one with a real success — is `PushSendOutcome.SKIPPED`
 * (openbank-notification-service): a disabled push adapter returned a *successful* skipped
 * result, the fan-out counted it as delivered, and an environment with no push credentials was
 * indistinguishable from a working one until `SKIPPED` got its own value. [EMPTY] is that same
 * fix applied to a feed fetch.
 *
 * Only [FETCHED] may ever record a success against the `feed-<name>` liveness entry
 * ([com.openbank.libs.observability.DomainMetrics.registerWorkflowLiveness], ADR-0237 point 2) —
 * [EMPTY], [HTTP_ERROR] and [PARSE_ERROR] must never record one, even though [EMPTY] and
 * [PARSE_ERROR] both complete the HTTP call itself without throwing past the client.
 */
enum class FeedFetchOutcome {
    /** A 2xx response, parsed successfully, with at least one row relevant to this feed's configured scope. */
    FETCHED,

    /**
     * A 2xx response, parsed successfully, but zero rows relevant to this feed's configured
     * scope — the "200 with nothing usable" case a job-ran heartbeat cannot see, e.g. the feed's
     * own currency set no longer overlaps the ones this service ingests.
     */
    EMPTY,

    /**
     * The HTTP call itself failed: a non-2xx status, a transport failure, a `@Timeout`, or an open
     * `@CircuitBreaker`. Never raised by the parser — the body was never obtained, or was rejected
     * before parsing was attempted.
     */
    HTTP_ERROR,

    /**
     * The HTTP call returned a 2xx response, but the body could not be parsed as the feed's
     * declared format — including a "soft 404" (a 200 status carrying an HTML error page instead
     * of the feed, the exact shape ČNB served for #2204).
     */
    PARSE_ERROR,
}
