// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.domain.model

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class Balance(
    val id: UUID,
    val accountId: UUID,
    val currency: String,
    val bookedAmount: BigDecimal,
    val availableAmount: BigDecimal,
    val reservedAmount: BigDecimal,
    val pendingAmount: BigDecimal,
    val updatedAt: OffsetDateTime,
    val version: Long,
    // Arranged (povolený) overdraft limit per ČNB/AnaCredit. The balance may be drawn down to
    // -arrangedOverdraftLimit; beyond that is an unarranged (nepovolený) overdraft and is rejected.
    val arrangedOverdraftLimit: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(arrangedOverdraftLimit.signum() >= 0) {
            "arrangedOverdraftLimit must be non-negative: $arrangedOverdraftLimit"
        }
    }

    // Lowest value bookedAmount/availableAmount may reach: the negated arranged overdraft limit.
    private fun overdraftFloor(): BigDecimal = arrangedOverdraftLimit.negate()

    fun available(): BigDecimal = availableAmount
    fun booked(): BigDecimal = bookedAmount
    fun reserved(): BigDecimal = reservedAmount

    /** Drawn overdraft (credit exposure for AnaCredit): how far booked is below zero, else zero. */
    fun overdraftUsed(): BigDecimal = bookedAmount.negate().max(BigDecimal.ZERO)

    fun isOverdrawn(): Boolean = bookedAmount.signum() < 0

    fun withReservation(amount: BigDecimal): Balance {
        require(availableAmount - amount >= overdraftFloor()) {
            "Insufficient funds: available=$availableAmount, overdraftLimit=$arrangedOverdraftLimit, requested=$amount"
        }
        return copy(
            availableAmount = availableAmount - amount,
            reservedAmount = reservedAmount + amount,
            version = version + 1,
        )
    }

    fun releaseReservation(amount: BigDecimal): Balance {
        val release = amount.min(reservedAmount)
        return copy(
            availableAmount = availableAmount + release,
            reservedAmount = reservedAmount - release,
            version = version + 1,
        )
    }

    fun applyDebit(amount: BigDecimal): Balance {
        require(bookedAmount - amount >= overdraftFloor()) {
            "Overdraft limit exceeded: booked=$bookedAmount, overdraftLimit=$arrangedOverdraftLimit, requested=$amount"
        }
        return copy(
            bookedAmount = bookedAmount - amount,
            availableAmount = availableAmount - amount,
            version = version + 1,
        )
    }

    fun applyCredit(amount: BigDecimal): Balance = copy(
        bookedAmount = bookedAmount + amount,
        availableAmount = availableAmount + amount,
        version = version + 1,
    )

    // ADR-0039 Phase D: apply a signed booked delta projected from a ledger AccountBookedChanged
    // event (+ on a credit, − on a debit). Unlike applyDebit there is NO overdraft guard: this is
    // not a new spend decision but the read-model catching up to a fact the ledger already posted —
    // the cover decision was enforced earlier by the hold, and a posted accounting movement cannot
    // be refused by its projection. Moves availableAmount in lock-step so the projected available
    // tracks booked; reservations/holds are layered on top by the saga, not here.
    fun applyBookedDelta(delta: BigDecimal): Balance = copy(
        bookedAmount = bookedAmount + delta,
        availableAmount = availableAmount + delta,
        version = version + 1,
    )
}

data class BalanceHold(
    val id: UUID,
    val accountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val reason: String,
    val referenceId: String,
    val expiresAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val releasedAt: OffsetDateTime?,
)

enum class BalanceEventType {
    BALANCE_UPDATED,
    HOLD_PLACED,
    HOLD_RELEASED,
    HOLD_EXPIRED,
}

data class BalanceEvent(
    val eventId: UUID,
    val eventType: BalanceEventType,
    val accountId: UUID,
    val currency: String,
    val amount: BigDecimal?,
    val bookedAmount: BigDecimal,
    val availableAmount: BigDecimal,
    val reservedAmount: BigDecimal,
    val occurredAt: OffsetDateTime,
)
