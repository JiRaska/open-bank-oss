// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.domain.event

import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Emitted when a transaction completes its saga (hold placed, journal posted, COMPLETED).
 * Carries the ledger [journalId] for reconciliation against the scheme's pacs.002/camt.054 and
 * the [originatingPaymentId] so the originating rail can transition its payment to COMPLETED.
 * Replaces [TransactionCompletedEvent] on the settlement path; the old event is retained for
 * backwards compatibility with existing consumers.
 */
data class TransactionSettledEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val referenceNumber: String,
    val journalId: UUID,
    val originatingPaymentId: UUID?,
    val bookingDate: LocalDate,
    val settledAt: Instant,
) : DomainEvent() {
    override val aggregateType = "Transaction"
    override val eventType = "TransactionSettled"
}
