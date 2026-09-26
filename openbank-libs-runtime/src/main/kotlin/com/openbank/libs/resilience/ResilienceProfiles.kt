// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.resilience

/**
 * The four named resilience profiles of ADR-0321 D1, as compile-time constants usable directly as
 * SmallRye / MicroProfile Fault Tolerance annotation arguments:
 *
 * ```kotlin
 * @ResilienceProfile(ResilienceProfiles.READ)
 * @Timeout(ResilienceProfiles.Read.TIMEOUT_MS)
 * @Retry(maxRetries = ResilienceProfiles.Read.MAX_RETRIES, delay = ResilienceProfiles.Read.DELAY_MS,
 *        jitter = ResilienceProfiles.Read.JITTER_MS)
 * ```
 *
 * The values are the ADR's table, which in turn are the modal literals already in the tree, so
 * adopting a profile changes behaviour for the outliers only. Durations are milliseconds (the
 * annotations' default `ChronoUnit.MILLIS`). Per-environment tuning uses MicroProfile FT's own
 * config override (`<class>/<method>/Timeout/value`) — never a new literal.
 *
 * Only [MoneySync] is keyed-only: its `@Retry` must use
 * `retryOn = [RetryableKeyedCallException::class], abortOn = [UpstreamCallException::class]` together
 * with [KeyedCallFilter] and [KeyedCall.invoke], because "retry only when the call carries an
 * idempotency key" is a per-call property that no static constant can express.
 */
object ResilienceProfiles {
    const val MONEY_SYNC = "money-sync"
    const val READ = "read"
    const val EXTERNAL_SCHEME = "external-scheme"
    const val BATCH = "batch"

    /** The declared deviation — only with a non-blank `reason` on [ResilienceProfile]. */
    const val CUSTOM = "custom"

    /** The closed set of profile names, [CUSTOM] included. */
    val ALL: Set<String> = setOf(MONEY_SYNC, READ, EXTERNAL_SCHEME, BATCH, CUSTOM)

    /** Synchronous money-path writes and gates (sanctions, ledger posting, SCA). */
    object MoneySync {
        const val TIMEOUT_MS = 3_000L
        const val MAX_RETRIES = 1
        const val DELAY_MS = 200L
        const val JITTER_MS = 100L
        const val CB_REQUEST_VOLUME_THRESHOLD = 10
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 5_000L
        const val CB_SUCCESS_THRESHOLD = 2
        const val BULKHEAD = 20
    }

    /** Idempotent reads (directory, catalog, balances). */
    object Read {
        const val TIMEOUT_MS = 2_000L
        const val MAX_RETRIES = 2
        const val DELAY_MS = 200L
        const val JITTER_MS = 100L
        const val CB_REQUEST_VOLUME_THRESHOLD = 10
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 5_000L

        /** Not specified by the ADR for this profile; MicroProfile FT's own default. */
        const val CB_SUCCESS_THRESHOLD = 1
        const val BULKHEAD = 50
    }

    /** Clearing, SEPA/SWIFT, CNB, any `@SyntheticTaintExternalBoundary` client. Idempotent calls only. */
    object ExternalScheme {
        const val TIMEOUT_MS = 10_000L
        const val MAX_RETRIES = 2
        const val DELAY_MS = 1_000L
        const val JITTER_MS = 500L
        const val CB_REQUEST_VOLUME_THRESHOLD = 4
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 10_000L
        const val CB_SUCCESS_THRESHOLD = 2
        const val BULKHEAD = 10
    }

    /** Scheduled and back-office calls with no user waiting. */
    object Batch {
        const val TIMEOUT_MS = 30_000L
        const val MAX_RETRIES = 3
        const val DELAY_MS = 2_000L
        const val JITTER_MS = 1_000L
        const val CB_REQUEST_VOLUME_THRESHOLD = 10
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 30_000L

        /** Not specified by the ADR for this profile; MicroProfile FT's own default. */
        const val CB_SUCCESS_THRESHOLD = 1
        const val BULKHEAD = 5
    }
}
