// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

/**
 * Makes the ORIGINAL transport fault of a resilient call visible, before fault tolerance masks it.
 *
 * MicroProfile Fault Tolerance applies its annotations in a fixed order —
 * `Fallback > Retry > CircuitBreaker > Timeout > Bulkhead` — so `@Retry` sits OUTSIDE
 * `@CircuitBreaker`. Once the breaker opens mid-retry, the remaining attempts fail with
 * `CircuitBreakerOpenException`, and that is what `@Retry` finally propagates. The 500, the
 * timeout, the connection refusal that actually caused it is discarded along with the earlier
 * attempts, so the outer `catch` has nothing to log but "the breaker is open" (#3267).
 *
 * The cost of that is not abstract: a domestic payment was held on 2026-08-02 and six hours of the
 * owning service's logs contained exactly one line about sanctions, naming nothing. The real cause
 * (`duplicate key value violates unique constraint "sanctions_checks_idempotency_key_key"`) was
 * only findable in a DIFFERENT service's log. On a rail where a screening failure holds customer
 * money, that is the wrong place for the evidence to live.
 *
 * Wrapping the call body — INSIDE the annotated method, so it runs per attempt and inside the
 * breaker — means every attempt reports its own fault as it happens.
 *
 * The failure handler is a parameter rather than a logger on purpose. It keeps this module free of
 * a logging opinion, and it makes the behaviour assertable in a plain unit test: a test can pin
 * "the underlying throwable was surfaced" without capturing log output, which is what #3267 asks
 * for ("the assertion has to be on what was logged, or the change is unverified").
 *
 * Applied to `openbank-domestic-payment`'s `SanctionsScreeningAdapter` first. The pattern is
 * fleet-wide — 57 files stack `@Retry` over `@CircuitBreaker` and only 7 mention a warn/error log
 * at all — so the remaining adapters are a follow-up sweep, not a blind 57-file refactor.
 */
// TooGenericExceptionCaught: catching narrowly is precisely the defect. A Timeout surfaces as an
// InterruptedException on some paths, a breaker rejection is an unchecked RuntimeException, and the
// transport faults this exists to reveal are whatever the rest-client threw. Nothing is swallowed —
// every throwable is rethrown unchanged, so fault tolerance still counts it.
@Suppress("TooGenericExceptionCaught")
suspend fun <T> reportingFirstFailure(onFailure: (Throwable) -> Unit, block: suspend () -> T): T = try {
    block()
} catch (ex: Throwable) {
    // Throwable, not Exception: a Timeout surfaces as an InterruptedException on some paths and a
    // breaker rejection is an unchecked RuntimeException — narrowing here would silently re-create
    // the blind spot for exactly the faults this exists to show.
    onFailure(ex)
    throw ex
}
