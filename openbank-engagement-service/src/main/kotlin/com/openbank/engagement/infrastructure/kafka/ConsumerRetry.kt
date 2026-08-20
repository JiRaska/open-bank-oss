// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.kafka

import kotlinx.coroutines.delay
import org.jboss.logging.Logger

/**
 * Bounded retry shared by this service's five event consumers (#5698).
 *
 * All five had the same shape: ONE `try` wrapping both the JSON/field parsing and the
 * `AdverseStateRepository` (or `CampaignBannerPlacementRepository`) write, with a single
 * `catch (e: Exception)` that logged and returned normally — which acks the Kafka message. Each
 * carried a comment justifying it as poison-pill safety, "the producer's outbox remains the source
 * of truth and can be replayed". Nothing replays it: the message was acked, and the only trace of
 * the lost work was an ERROR line nobody alerts on.
 *
 * The justification is right about parsing and wrong about the write. A malformed payload is
 * unretryable — replaying it produces the same parse failure forever — so each consumer still parses
 * in its own `catch` and acks. The repository write is the opposite case: the event is fine, the
 * database is not, and the work must still happen once it recovers. It now runs here, is retried
 * [MAX_ATTEMPTS] times with linear backoff, and is RETHROWN if it still fails, so the connector
 * dead-letters rather than silently dropping a targeting-exclusion signal (a fraud hold, an arrears
 * flag, a dispute, or a GDPR erasure that ADR-0220 D3.5 treats as terminal and never re-derives).
 *
 * Every write behind this helper is idempotent — `setActive`/`clearActive` are keyed by
 * (party, state) and `save` upserts by interaction ref — so both the retry and a connector
 * redelivery are safe.
 */
internal const val MAX_ATTEMPTS = 3

/** Linear backoff base: attempt N waits N * this. */
internal const val RETRY_BACKOFF_MS = 500L

/**
 * Run [block], retrying a failure up to [MAX_ATTEMPTS] times and then RETHROWING.
 *
 * The rethrow is the point. A caught-and-logged failure acks the message, and an acked message that
 * did no work is indistinguishable from one that succeeded — from Kafka, from the consumer lag
 * metric, and from every dashboard built on either. The retry stays bounded and the failure moves to
 * the DLQ, so one bad event still cannot wedge the consumer group.
 */
@Suppress("TooGenericExceptionCaught") // the retry is type-agnostic on purpose: any failure of the
// write is a failure to apply the signal, and the bounded rethrow (not a swallow) keeps it visible.
internal suspend fun withBoundedRetry(log: Logger, what: String, block: suspend () -> Unit) {
    var attempt = 1
    while (true) {
        try {
            block()
            return
        } catch (e: Exception) {
            if (attempt >= MAX_ATTEMPTS) {
                log.errorf(
                    e,
                    "%s failed after %d attempts (%s: %s) — rethrowing so the connector dead-letters",
                    what,
                    attempt,
                    e.javaClass.simpleName,
                    e.message,
                )
                throw e
            }
            log.warnf(
                "%s failed (attempt %d/%d, %s: %s) — retrying",
                what,
                attempt,
                MAX_ATTEMPTS,
                e.javaClass.simpleName,
                e.message,
            )
            delay(RETRY_BACKOFF_MS * attempt)
            attempt++
        }
    }
}
