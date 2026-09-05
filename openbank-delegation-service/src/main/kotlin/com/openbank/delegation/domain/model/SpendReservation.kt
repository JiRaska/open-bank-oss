// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.money.Money
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0249 D3 — lifecycle of one unit of delegated spend.
 *
 * RESERVED and CONFIRMED both consume headroom; only RELEASED gives it back. That asymmetry is the
 * whole mechanism: the ceiling is checked and the headroom is taken BEFORE the money moves, so two
 * payments that would jointly breach a ceiling cannot both pass a check that each passes alone.
 * Counting after settlement — the obvious cheaper design — cannot do that, and a limit noticed
 * afterwards is not a limit.
 */
enum class SpendReservationState {
    RESERVED,
    CONFIRMED,
    RELEASED,
}

/** Identifies the rail that owns reconciliation; it is not workload authentication. */
enum class SpendReservationOperationType {
    UNSPECIFIED,
    DOMESTIC_PAYMENT,
}

const val MAX_RESERVATION_IDEMPOTENCY_KEY_LENGTH = 200
const val MAX_DOMESTIC_PAYMENT_IDEMPOTENCY_KEY_LENGTH = 128

fun validateSpendReservationIdempotencyKey(idempotencyKey: String, operationType: SpendReservationOperationType) {
    require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
    val maximum = when (operationType) {
        SpendReservationOperationType.UNSPECIFIED -> MAX_RESERVATION_IDEMPOTENCY_KEY_LENGTH
        SpendReservationOperationType.DOMESTIC_PAYMENT -> MAX_DOMESTIC_PAYMENT_IDEMPOTENCY_KEY_LENGTH
    }
    require(idempotencyKey.codePointCount(0, idempotencyKey.length) <= maximum) {
        "idempotencyKey must be at most $maximum characters for $operationType"
    }
}

/**
 * One reservation against a [DelegationGrant]'s cumulative ceilings.
 *
 * [amount] is a [Money], the fleet's only sanctioned money type: an exact `BigDecimal` scaled to
 * the currency's minor unit. Nothing here is ever a `Double`, and no arithmetic in this file
 * widens past `BigDecimal`.
 *
 * [idempotencyKey] is the caller's key for this spend, unique per grant. A retried reserve — the
 * edge timing out and re-sending, a rail replaying — must return the SAME reservation rather than
 * take the headroom twice. The uniqueness is enforced by a database constraint, not by a lookup:
 * see `SpendReservationRepository.reserve`.
 */
data class SpendReservation(
    val id: UUID = Ids.newId(),
    val grantId: UUID,
    val amount: Money,
    val idempotencyKey: String,
    val operationType: SpendReservationOperationType = SpendReservationOperationType.UNSPECIFIED,
    val state: SpendReservationState = SpendReservationState.RESERVED,
    val createdAt: OffsetDateTime,
    val settledAt: OffsetDateTime? = null,
) {
    init {
        validateSpendReservationIdempotencyKey(idempotencyKey, operationType)
        require(amount.isPositive()) { "a reservation amount must be positive" }
        require((state == SpendReservationState.RESERVED) == (settledAt == null)) {
            "settledAt is set exactly when the reservation has left RESERVED (is $state)"
        }
    }

    /** RESERVED and CONFIRMED count; RELEASED does not. */
    val countsTowardCeilings: Boolean
        get() = state != SpendReservationState.RELEASED

    fun confirm(now: OffsetDateTime): SpendReservation {
        check(state == SpendReservationState.RESERVED) {
            "only a RESERVED reservation can be confirmed (is $state)"
        }
        return copy(state = SpendReservationState.CONFIRMED, settledAt = now)
    }

    /**
     * Releasing gives the headroom back, so it is refused once the money has moved: a CONFIRMED
     * reservation that could be released would silently re-open a ceiling the delegate has already
     * spent through. The failure this guards is the one ADR-0249's consequences section names —
     * a leaked lifecycle shrinking or inflating a customer's limit without anyone noticing.
     */
    fun release(now: OffsetDateTime): SpendReservation {
        check(state == SpendReservationState.RESERVED) {
            "only a RESERVED reservation can be released (is $state)"
        }
        return copy(state = SpendReservationState.RELEASED, settledAt = now)
    }
}
