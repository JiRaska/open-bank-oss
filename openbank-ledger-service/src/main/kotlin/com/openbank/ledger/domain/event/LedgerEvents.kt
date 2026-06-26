// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.domain.event

import com.openbank.libs.domain.event.DomainEvent
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class JournalPostedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val entryNumber: Long,
    val transactionId: UUID,
    val entryDate: LocalDate,
    val lineCount: Int,
) : DomainEvent() {
    override val aggregateType = "JournalEntry"
    override val eventType = "JournalPosted"
}

data class JournalReversedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val originalJournalId: UUID,
    val transactionId: UUID,
    val reason: String,
) : DomainEvent() {
    override val aggregateType = "JournalEntry"
    override val eventType = "JournalReversed"
}

/**
 * A signed booked-balance change for one customer account+currency, emitted once per affected
 * deposit-control account when a journal is posted or reversed (ADR-0039 Phase D). balance-service's
 * (currently projection-gated) consumer applies these as the booked read-model; the field names
 * MUST match what LedgerProjectionConsumer.toChange() reads. [aggregateId] is the customer accountId.
 */
data class AccountBookedChangedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val currency: String,
    val delta: BigDecimal,
    val journalEntryId: UUID,
    val transactionId: UUID,
    val entryDate: LocalDate,
) : DomainEvent() {
    override val aggregateType = "Account"
    override val eventType = "AccountBookedChanged"
}
