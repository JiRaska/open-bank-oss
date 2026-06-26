// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.application.port.out

import com.openbank.transaction.domain.model.Transaction

/**
 * Outbound port for serialising transaction domain events into their JSON payload form.
 * Transport to Kafka is handled by [com.openbank.libs.persistence.outbox.OutboxEventPublisher]
 * via the outbox dispatcher (ADR-0049 D3).
 */
interface TransactionEventPublisher {

    fun initiatedPayload(transaction: Transaction): String

    fun completedPayload(transaction: Transaction): String

    fun failedPayload(transaction: Transaction, reason: String): String

    /** ADR-0108: settlement proof event with journalId for scheme reconciliation. */
    fun settledPayload(transaction: Transaction, journalId: java.util.UUID): String
}
