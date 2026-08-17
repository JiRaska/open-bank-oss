// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.schedule

import com.openbank.fx.application.port.`in`.CnbIngestionResult
import com.openbank.libs.observability.FeedFetchOutcome
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException
import java.io.IOException

/**
 * Classifies one ČNB fixing ingestion attempt into a [FeedFetchOutcome] (ADR-0237 point 2, #4743).
 *
 * Pure and side-effect-free so both halves are directly testable — which matters, because the whole
 * value of the contract is that these cases stop being one indistinguishable "the age gauge went
 * stale". It lives in `infrastructure` rather than `domain` on purpose: it reads JAX-RS and
 * MicroProfile Fault Tolerance exception types, and the domain layer has zero framework imports.
 *
 * **What the pre-#4743 code did with each of these:** a 404, a bad payload and a timeout all landed
 * in the scheduler's single `catch`, produced one ERROR line each, and skipped `recordSuccess()` —
 * so all three read identically downstream, and identically to "the job never ran". The fourth case
 * ([FeedFetchOutcome.EMPTY]) had no signal at all: it *recorded success*.
 */
internal object CnbFetchOutcomes {

    /**
     * Classifies a completed ingestion.
     *
     * [FeedFetchOutcome.EMPTY] is the case with no prior signal and is narrower than "stored
     * nothing": an idempotent re-run reports `ingested = 0, skipped = N`, and that is
     * [FeedFetchOutcome.FETCHED] — the rates arrived, we already had them. Empty means the run
     * touched **no** configured-currency rate at all, `ingested == 0 && skipped == 0`, which happens
     * when the feed is well-formed but carries none of `openbank.cnb.currencies` — an upstream that
     * renamed or dropped a code. Today that path calls `recordSuccess()` and is indistinguishable
     * from a healthy ingestion, in the metric and in the log.
     *
     * Note that a feed with *no rate lines whatsoever* never reaches here: `CnbFixingParser`
     * `require`s a non-empty rate list and throws, so it is classified by [ofFailure] as
     * [FeedFetchOutcome.PARSE_ERROR]. Empty-document and empty-for-us are different upstream
     * behaviours and stay different outcomes.
     */
    fun of(result: CnbIngestionResult): FeedFetchOutcome =
        if (result.ingested == 0 && result.skipped == 0) FeedFetchOutcome.EMPTY else FeedFetchOutcome.FETCHED

    /**
     * Classifies a thrown ingestion.
     *
     * The three failure outcomes are genuinely different upstream behaviours for what a human calls
     * "the feed is broken", and the ČNB is the reason the distinction is not academic: it serves its
     * own 404 as a 58 KB HTML page, so one misconfigured URL can present as [FeedFetchOutcome.HTTP_ERROR]
     * or — if a proxy or a CDN turns it into a 200 — as [FeedFetchOutcome.PARSE_ERROR], and only the
     * counter says which. [FeedFetchOutcome.UNREACHABLE] is kept separate for the reason
     * `check-external-feeds.py`'s own triage keeps it separate: unreachable is not a verdict about
     * the feed, it is the absence of one, and `apl.cnb.cz` really does answer some network positions
     * and not others.
     *
     * Anything unrecognised classifies as [FeedFetchOutcome.PARSE_ERROR] — the local, in-process
     * bucket — rather than as an upstream fault. Guessing "the third party is down" from an
     * exception we do not recognise would put blame on the feed for what is most likely our own
     * ingestion code, and a wrong attribution in a triage metric is worse than a vague one.
     */
    fun ofFailure(ex: Throwable): FeedFetchOutcome = when (ex) {
        is WebApplicationException -> FeedFetchOutcome.HTTP_ERROR
        is CircuitBreakerOpenException, is TimeoutException, is IOException -> FeedFetchOutcome.UNREACHABLE
        // The parser's own contract: `require(...)` on an empty feed, a missing header or no rate
        // lines, plus the date header failing to parse out of an HTML error page.
        is IllegalArgumentException -> FeedFetchOutcome.PARSE_ERROR
        else -> ex.cause?.let { if (it !== ex) ofFailure(it) else null } ?: FeedFetchOutcome.PARSE_ERROR
    }
}
