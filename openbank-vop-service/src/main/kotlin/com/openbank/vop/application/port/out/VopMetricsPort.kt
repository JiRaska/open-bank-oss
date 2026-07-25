// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.application.port.out

import com.openbank.vop.domain.model.VopVerification
import java.time.Duration

/**
 * Outbound observability port (ADR-0002 / ADR-0077 Tier C) for Verification of Payee (ADR-0171).
 *
 * VoP's two load-bearing behaviours are both **deliberately silent**, and that is why they need
 * meters rather than log lines:
 *
 *  - the service **fails open** (ADR-0171 §3): a down account-service or party-service yields
 *    `NO_DATA / LOOKUP_UNAVAILABLE` and a WARN, and the payment proceeds. From the outside a
 *    total lookup outage is indistinguishable from a bank whose customers all pay strangers —
 *    `no_data{reason=lookup_unavailable}` is the difference.
 *  - the rate limiter **fails closed** (threat model §4.1): if Valkey is unreachable every
 *    requester is rejected, which is correct and also invisible. `rate_limit{outcome=throttled}`
 *    is additionally the only way to see enumeration pressure at all, and the ADR's own note on
 *    the 60/min default says to "tune from the outcome metrics" — metrics that did not exist.
 *
 * Kept as a port rather than a direct `MeterRegistry` dependency so the application layer stays
 * free of the metrics framework, and so the counters are exercised through the real adapter (over
 * a `SimpleMeterRegistry`) in unit tests.
 *
 * Implemented by [com.openbank.vop.infrastructure.observability.VopMetricsAdapter].
 */
interface VopMetricsPort {

    /**
     * Record one completed verification: its route, its outcome (and NO_DATA reason), and the
     * end-to-end latency the caller waited — IPR Art. 5c puts VoP on the payment-initiation path,
     * so its latency is the payer's latency.
     */
    fun verificationCompleted(route: VopRoute, verification: VopVerification, duration: Duration)

    /** Record one rate-limit decision on the verify endpoint. */
    fun rateLimitDecision(outcome: VopRateLimitOutcome)
}

/** Which of the two ADR-0171 §4 routes answered a verification. A bounded set — safe as a tag. */
enum class VopRoute {
    /** Our own IBAN space: resolved locally via account-service → party-service. */
    DOMESTIC,

    /** Someone else's IBAN: handed to the EPC VoP scheme port. */
    EXTERNAL,
}

/** Outcome of the per-requester rate-limit check. A bounded set — safe as a tag. */
enum class VopRateLimitOutcome {
    /** Under the limit; the request proceeded. */
    ALLOWED,

    /** Over the limit; rejected with 429. Sustained non-zero is enumeration pressure. */
    THROTTLED,

    /** The Valkey window was unreachable, so the request was rejected fail-closed. */
    STORE_UNAVAILABLE,
}
