// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.application.port.out

import com.openbank.sepa.domain.model.SepaPayment
import java.util.UUID

interface SettlementPort {
    /** Book the outbound debit for a SEPA payment that the scheme has accepted (ACSC). */
    suspend fun settle(payment: SepaPayment): SettlementOutcome
}

data class SettlementOutcome(val settled: Boolean, val transactionId: UUID?)

/** Thrown when transaction-service is unreachable; caller keeps payment in PROCESSING. */
class SettlementUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
