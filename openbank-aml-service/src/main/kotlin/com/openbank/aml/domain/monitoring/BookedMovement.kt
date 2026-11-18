// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.monitoring

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Direction of a booked movement as seen from the customer account. */
enum class MovementDirection { IN, OUT }

/**
 * One booked change of one customer account in one currency (ADR-0371 §1).
 *
 * The identity is the ledger's own business key — journal entry, account, currency — and not the
 * transport event id: the ledger can re-emit a historical booking under a fresh event id, and a
 * counter that deduplicated on the transport id would count that booking twice.
 */
data class BookedMovement(
    val journalEntryId: UUID,
    val accountId: UUID,
    val currency: String,
    val direction: MovementDirection,
    /** Always positive; the sign lives in [direction]. Never converted to another currency. */
    val amount: BigDecimal,
    val transactionId: UUID,
    val entryDate: LocalDate,
    val bookedAt: Instant,
    val sourceEventId: UUID?,
) {
    init {
        require(amount.signum() > 0) { "a booked movement has a positive amount" }
        require(currency.matches(CURRENCY)) { "currency is three upper-case letters" }
    }

    companion object {
        private val CURRENCY = Regex("[A-Z]{3}")

        /** Credit-positive delta, as the ledger publishes it: positive is money in. */
        fun fromSignedDelta(
            journalEntryId: UUID,
            accountId: UUID,
            currency: String,
            delta: BigDecimal,
            transactionId: UUID,
            entryDate: LocalDate,
            bookedAt: Instant,
            sourceEventId: UUID?,
        ): BookedMovement {
            require(delta.signum() != 0) { "a zero delta is not a movement" }
            return BookedMovement(
                journalEntryId = journalEntryId,
                accountId = accountId,
                currency = currency,
                direction = if (delta.signum() > 0) MovementDirection.IN else MovementDirection.OUT,
                amount = delta.abs(),
                transactionId = transactionId,
                entryDate = entryDate,
                bookedAt = bookedAt,
                sourceEventId = sourceEventId,
            )
        }
    }
}

/** A recorded movement that has not been attributed to a party and rail yet. */
data class UncountedMovement(
    val journalEntryId: UUID,
    val accountId: UUID,
    val currency: String,
    val direction: MovementDirection,
    val amount: BigDecimal,
    val transactionId: UUID,
    val bookedAt: Instant,
    val recordedAt: Instant,
    /** Party already known for the account from an earlier resolution, if any. */
    val knownPartyId: UUID?,
    /** Rail of the originating transaction, if its event has arrived. */
    val originRail: String?,
)
