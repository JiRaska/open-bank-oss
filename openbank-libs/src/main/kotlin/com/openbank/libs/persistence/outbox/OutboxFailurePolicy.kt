// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.persistence.outbox

/**
 * Canonical terminal-vs-retry decision for a failed outbox publish (ADR-0050 N5).
 *
 * This is the one place the dead-letter threshold lives. Previously every service
 * copied a `statusAfterFailure` with its own `MAX_ATTEMPTS` constant (and several
 * services had no dead-letter transition at all — a row could be retried forever and
 * starve the batch). A repository's `markFailed` increments the attempt counter and
 * then asks this policy what the row's next status is.
 *
 * Pure function — unit-tested, no framework or I/O.
 */
object OutboxFailurePolicy {
    /** Publish attempts before a row is parked as [OutboxStatus.DEAD] (ADR-0050 N5). */
    const val DEFAULT_MAX_ATTEMPTS: Int = 10

    /** Max stored length of `last_error`; longer messages are truncated by callers. */
    const val MAX_ERROR_LEN: Int = 4000

    /**
     * Once [attemptCount] reaches [maxAttempts] the row becomes terminal [OutboxStatus.DEAD]
     * and is never re-dispatched; otherwise it stays [OutboxStatus.FAILED] and is retried on
     * a later tick. [attemptCount] is the count *after* the failing attempt has been recorded.
     */
    fun statusAfterFailure(attemptCount: Int, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): OutboxStatus =
        if (attemptCount >= maxAttempts) OutboxStatus.DEAD else OutboxStatus.FAILED
}
