// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0039 Phase D dedup ledger (table `ledger_projection_event`). One row per applied
 * AccountBookedChanged event, keyed by the journal entry plus the (account, currency) it moved.
 * Written in the SAME transaction as the balance update, so a redelivery that finds the row present
 * is skipped — the booked balance can never be double-applied.
 */
@Entity
@Table(name = "ledger_projection_event")
@IdClass(LedgerProjectionEventId::class)
class LedgerProjectionEventEntity {

    @Id
    @Column(name = "journal_entry_id", nullable = false)
    lateinit var journalEntryId: UUID

    @Id
    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Id
    @Column(nullable = false, length = 3)
    lateinit var currency: String

    @Column(nullable = false, precision = 19, scale = 4)
    lateinit var delta: BigDecimal

    @Column(name = "transaction_id", nullable = false)
    lateinit var transactionId: UUID

    @Column(name = "entry_date", nullable = false)
    lateinit var entryDate: LocalDate

    @Column(name = "applied_at", nullable = false)
    var appliedAt: OffsetDateTime = OffsetDateTime.MIN
}

/** Composite primary key for [LedgerProjectionEventEntity]. */
data class LedgerProjectionEventId(
    var journalEntryId: UUID = UUID(0, 0),
    var accountId: UUID = UUID(0, 0),
    var currency: String = "",
) : Serializable
