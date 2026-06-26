// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.domestic.application.port.out

import com.openbank.domestic.domain.model.DomesticPayment
import java.util.UUID

/**
 * Out-port for booking the funds via transaction-service after the scheme confirms ACSC
 * (ADR-0108). A successful outcome carries the [transactionId] that was booked; the adapter
 * is idempotent — a duplicate call with the same [DomesticPayment.id] returns the same
 * outcome without double-booking.
 */
interface SettlementPort {
    suspend fun settle(payment: DomesticPayment): SettlementOutcome
}

/** The result of a settlement attempt. [settled] is true on first-book and 409-idempotent. */
data class SettlementOutcome(val settled: Boolean, val transactionId: UUID?)

/**
 * Thrown when transaction-service is reachable but returns an unrecoverable error (5xx, parse
 * failure, etc.). The use-case holds the payment in SENT_TO_CLEARING and retries via the
 * Temporal activity retry policy rather than self-settling without a confirmed booking.
 */
class SettlementUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
