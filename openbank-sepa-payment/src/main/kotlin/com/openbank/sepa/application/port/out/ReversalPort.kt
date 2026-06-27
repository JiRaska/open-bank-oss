// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.port.out

import java.util.UUID

/**
 * Outbound port to reverse a previously booked transaction in the ledger (transaction-service).
 * Used when an inbound pacs.004 return triggers a ledger credit back to the debtor account.
 */
interface ReversalPort {
    suspend fun reverseTransaction(transactionId: UUID, idempotencyKey: String, reason: String): ReversalOutcome
}

data class ReversalOutcome(val reversed: Boolean, val reversalTransactionId: UUID?)

class ReversalUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
