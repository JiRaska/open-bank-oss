// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.kafka

import kotlinx.coroutines.delay
import org.jboss.logging.Logger

/**
 * Bounded retry shared by this service's two document-issuance consumers (#5698).
 *
 * Both used to catch `Exception` around the use-case call, log, and return normally — which acks
 * the Kafka message. The comment framed that as a deliberate trade-off: onboarding is off the money
 * path (ADR-0086), and a deterministic failure for ONE account (a product whose
 * `documentTemplateCode` has no PUBLISHED template) must not wedge issuance for every subsequent
 * account. That reasoning is sound and is preserved. What it could not do is tell a deterministic
 * failure from a transient one — the same generic catch swallowed a connection-refused from
 * Postgres, and an acked message that did no work is indistinguishable from one that succeeded.
 *
 * The split is by exception type, and it is not arbitrary: the deterministic per-event failures in
 * this service are raised as [IllegalStateException] (`DocumentRenderService`'s
 * `error("No published template for …")`) or [IllegalArgumentException] (a value the event itself
 * carries). Those can never succeed on retry, so they are rethrown immediately and the CALLER acks
 * them, keeping the documented behaviour. Everything else — a `PersistenceException`, a socket
 * failure, a timeout — is retried [MAX_ATTEMPTS] times and then rethrown, so the connector
 * dead-letters rather than losing the event.
 */
internal const val MAX_ATTEMPTS = 3

/** Linear backoff base: attempt N waits N * this. */
internal const val RETRY_BACKOFF_MS = 500L

/**
 * True for failures that are a property of the EVENT rather than of the infrastructure, and so
 * produce the identical failure on every redelivery. Retrying them burns attempts for nothing and
 * dead-lettering them buys nothing either — the caller acks them instead.
 */
internal fun Throwable.isDeterministicForThisEvent(): Boolean =
    this is IllegalStateException || this is IllegalArgumentException

/**
 * Run [block], retrying a transient failure up to [MAX_ATTEMPTS] times and then RETHROWING.
 *
 * The rethrow is the point: it is the only thing that turns a lost event into a visible one. A
 * deterministic failure ([isDeterministicForThisEvent]) is rethrown on the first attempt without a
 * retry, so the caller can distinguish it and ack.
 */
@Suppress("TooGenericExceptionCaught") // the classification below IS the specific-exception handling
internal suspend fun withBoundedRetry(log: Logger, what: String, block: suspend () -> Unit) {
    var attempt = 1
    while (true) {
        try {
            block()
            return
        } catch (e: Exception) {
            if (e.isDeterministicForThisEvent()) throw e
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
