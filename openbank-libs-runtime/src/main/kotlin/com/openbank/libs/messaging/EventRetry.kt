// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.messaging

import kotlinx.coroutines.delay
import org.jboss.logging.Logger

/**
 * Bounded retry for an event handler, ending in a RETHROW so the platform can see the failure.
 *
 * WHY THIS EXISTS
 *   A `suspend @Incoming` handler that returns normally ACKS its message. So a handler that catches
 *   its own failures and logs them tells Kafka the work is done — and an acked message that did no
 *   work is indistinguishable from a successful one: not in consumer lag, not on any dashboard built
 *   on lag, not in the DLQ, which stays empty. The only trace is an ERROR line, and nothing pages on
 *   ERROR lines.
 *
 *   On 2026-08-19 kyc-db was unreachable for a few seconds. One PARTY_CREATED arrived in that
 *   window, the handler logged `Failed to auto-open/screen KYC case` and acked. No KYC case was
 *   opened, so the party stayed PENDING_KYC, its two accounts stayed PENDING_ACTIVATION, and the
 *   welcome bonus — which fires only on activation — never ran. The customer had accounts that did
 *   not work and no money in them, and nothing anywhere was reporting it (#5698).
 *
 *   ONE confirmed instance, not the ten first reported. Of the nine other sandbox parties with no
 *   KYC case, six predate the auto-open consumer (added 2026-06-26) and three are e2e fixtures with
 *   no accounts at all. The bigger number was the alarming one and it was wrong; the defect is the
 *   same either way, which is why it is worth stating rather than quietly keeping.
 *
 * THE DISTINCTION THIS ENCODES
 *   A **malformed event** is unretryable: replaying it fails identically forever, so log-and-ack is
 *   right. That is the poison pill, and it is the ONLY case that may be acked on failure. Callers
 *   handle it before calling this — parse, validate, return.
 *
 *   Everything else is a dependency failing, not the event. Hexagonal architecture is precisely why
 *   that must not be logged away: a database or cache being down is a NORMAL event for an adapter,
 *   and the port contract is "the work happens, or somebody finds out".
 *
 * WHY BOUNDED, AND WHY IT ENDS IN A THROW
 *   Unbounded in-handler retry blocks the partition through a long outage, which is the fear the
 *   swallowing versions were written against. Bounding it and rethrowing hands the decision to the
 *   connector's own failure strategy (retry, then dead-letter): the work is preserved somewhere a
 *   human can find it, and the partition moves on.
 *
 * SCOPE
 *   Not for side effects that are genuinely optional — a push notification when the money is already
 *   booked. Those may be caught, but give them their own catch with a comment saying why the event
 *   is complete without them, rather than sharing a catch with the state change.
 */
object EventRetry {

    const val DEFAULT_MAX_ATTEMPTS = 3
    const val DEFAULT_BACKOFF_MS = 500L

    /**
     * Run [block], retrying transient failures, then rethrow.
     *
     * @param log the caller's logger, so the message names the real handler rather than this class.
     * @param what short description of the work — appears in both the retry and dead-letter lines.
     * @param key the entity the event is about (party id, account id, …), for grepping later.
     */
    @Suppress("TooGenericExceptionCaught") // The point of this helper: ANY failure is transient until proven otherwise.
    suspend fun <T> withRetry(
        log: Logger,
        what: String,
        key: Any?,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        backoffMs: Long = DEFAULT_BACKOFF_MS,
        block: suspend () -> T,
    ): T {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (attempt >= maxAttempts) {
                    log.errorf(
                        e,
                        "%s for %s failed after %d attempt(s) (%s: %s) — rethrowing so the connector dead-letters",
                        what,
                        key,
                        attempt,
                        e.javaClass.simpleName,
                        e.message,
                    )
                    throw e
                }
                log.warnf(
                    "%s for %s failed (attempt %d/%d, %s: %s) — retrying",
                    what,
                    key,
                    attempt,
                    maxAttempts,
                    e.javaClass.simpleName,
                    e.message,
                )
                delay(backoffMs * attempt)
                attempt++
            }
        }
    }
}
