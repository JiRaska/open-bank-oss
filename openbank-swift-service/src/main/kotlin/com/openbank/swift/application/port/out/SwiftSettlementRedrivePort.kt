// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.application.port.out

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * #13686: durable bookkeeping for re-driving the settlement of an accepted MT103 whose booking is unproven.
 *
 * Eligibility is the durable booking clock (`booking_first_due_at` set, `booking_proven_at` null) on a SENT
 * MT103, never SENT alone. A claim is an atomic conditional write of a fresh token and a lease: of any number
 * of concurrent callers at most one gets `true`, so at most one transaction-service call is in flight per
 * message. Every later write is conditional on that token, so a holder whose lease lapsed cannot overwrite
 * the next holder's state.
 */
interface SwiftSettlementRedrivePort {
    /** Ids eligible at [now] whose clock opened at or before [dueBefore], oldest first. A hint, not a claim. */
    suspend fun dueIds(now: Instant, dueBefore: Instant, limit: Int): List<UUID>

    /** Atomically claim [id] with [token] until [leaseUntil]; `false` if not eligible or held by someone else. */
    suspend fun claim(id: UUID, token: UUID, now: Instant, dueBefore: Instant, leaseUntil: Instant): Boolean

    /** Release a claim after the booking was proven. */
    suspend fun release(id: UUID, token: UUID)

    /**
     * Count one unproven attempt and release the claim. The next attempt waits [RedriveBackoff.delayAfter];
     * after [RedriveBackoff.maxAttempts] the message is exhausted and no longer swept (its booking clock stays
     * open, so the age gauge and `SwiftBookingUnsettled` keep reporting it). Null when [token] no longer
     * holds the claim.
     */
    suspend fun reschedule(id: UUID, token: UUID, now: Instant, backoff: RedriveBackoff): RescheduleOutcome?
}

/** Bounded exponential backoff: `min(base * 2^(n-1), max)` after the n-th unproven attempt. */
data class RedriveBackoff(val base: Duration, val max: Duration, val maxAttempts: Int) {
    init {
        require(!base.isNegative && !base.isZero) { "re-drive backoff base must be positive" }
        require(max >= base) { "re-drive backoff max must be at least the base" }
        require(maxAttempts > 0) { "re-drive max attempts must be positive" }
    }

    fun delayAfter(attempt: Int): Duration {
        var delay = base
        repeat((attempt - 1).coerceIn(0, MAX_DOUBLINGS)) { if (delay < max) delay = delay.multipliedBy(2) }
        return if (delay > max) max else delay
    }

    private companion object {
        const val MAX_DOUBLINGS = 30
    }
}

data class RescheduleOutcome(val attempts: Int, val nextAt: Instant?, val exhausted: Boolean)
