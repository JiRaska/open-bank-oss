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

    /**
     * Synchronous money-path writes and gates (sanctions, ledger posting, SCA).
     *
     * Measured 2026-09-27 (this PR): the one fleet `@CircuitBreaker` site whose other four values
     * match this profile's shape exactly (`Timeout(3000)` + `Retry(1, 200, 100)` +
     * `CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)`) is
     * `TppRegistryService.attemptEbaSync`, which sets `successThreshold = 2`. `N=1`; confirms the
     * existing constant, does not newly derive it.
     */
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

    /**
     * Idempotent reads (directory, catalog, balances).
     *
     * Measured 2026-09-27 (this PR, re-measuring PR #11072's finding): every fleet
     * `@CircuitBreaker` site whose other four values match this profile's shape exactly
     * (`Timeout(2000)` + `Retry(2, 200, 100)` +
     * `CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)`) sets
     * `successThreshold = 2`: `N=7` — `ScaChallengeClient.getChallenge` (account-service,
     * consent-service, delegation-service), `ResourceOwnershipClient.verifyOwnership` and
     * `PartyEligibilityClient.eligibilityOf` (delegation-service), `NotificationDispatchGuard
     * .sendPushNotification` (sca-service), `TppRegistryClient.requireAuthorized`
     * (psd2-service). ADR-0321 left this field blank for `read`; the fleet did not — `2`, not
     * MicroProfile FT's default `1`.
     */
    object Read {
        const val TIMEOUT_MS = 2_000L
        const val MAX_RETRIES = 2
        const val DELAY_MS = 200L
        const val JITTER_MS = 100L
        const val CB_REQUEST_VOLUME_THRESHOLD = 10
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 5_000L
        const val CB_SUCCESS_THRESHOLD = 2
        const val BULKHEAD = 50
    }

    /**
     * Clearing, SEPA/SWIFT, CNB, any `@SyntheticTaintExternalBoundary` client. Idempotent calls
     * only.
     *
     * Measured 2026-09-27 (this PR): no fleet site matches this profile's full shape exactly, but
     * the two sites sharing its circuit-breaker shape alone (`requestVolumeThreshold = 4,
     * failureRatio = 0.5, delay = 10_000`) — `CnbRateProviderAdapter.fetchWithResilience`
     * (fx-service) and `FxServiceCnbRateAdapter.fetchWithResilience` (ledger-service) — both set
     * `successThreshold = 2`. `N=2`; confirms the existing constant.
     */
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

    /**
     * Scheduled and back-office calls with no user waiting.
     *
     * Measured 2026-09-27 (this PR): zero fleet `@CircuitBreaker` sites carry this profile's
     * circuit-breaker shape (`requestVolumeThreshold = 10, failureRatio = 0.5, delay = 30_000`) —
     * the outbox dispatchers that share this profile's long `Timeout`/`Retry` values all use
     * `delay = 5_000` on their `@CircuitBreaker` instead, a different shape. With no fleet
     * population to measure, this constant is NOT a measured modal value like [Read]'s — it is
     * left at MicroProfile FT's own default (`1`) because there is nothing else to set it to.
     * Re-measure when a real `batch`-profile adopter exists.
     */
    object Batch {
        const val TIMEOUT_MS = 30_000L
        const val MAX_RETRIES = 3
        const val DELAY_MS = 2_000L
        const val JITTER_MS = 1_000L
        const val CB_REQUEST_VOLUME_THRESHOLD = 10
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 30_000L
        const val CB_SUCCESS_THRESHOLD = 1
        const val BULKHEAD = 5
    }
}
