// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.`in`

import com.openbank.delegation.domain.model.DomesticPaymentOutcome
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationOperationType
import com.openbank.libs.domain.money.Money
import java.util.UUID

/**
 * ADR-0249 D3. [idempotencyKey] is the caller's, not this service's: the edge already has a stable
 * key for the payment it is about to initiate, and reserve must be safe to retry under it.
 */
data class ReserveSpendCommand(
    val callerPartyId: CallerPartyId,
    val delegationId: UUID,
    val amount: Money,
    val idempotencyKey: String,
    val operationType: SpendReservationOperationType = SpendReservationOperationType.UNSPECIFIED,
)

/** [replayed] is true when the idempotency key had already produced this reservation. */
data class ReserveSpendResult(val reservation: SpendReservation, val replayed: Boolean)

interface ReserveSpendUseCase {
    /**
     * Takes headroom under all three ceilings, or throws
     * [com.openbank.delegation.application.usecase.SpendReservationRefusedException] naming the one
     * that was hit and what is left under it.
     */
    suspend fun reserve(command: ReserveSpendCommand): ReserveSpendResult

    /** Settles the reservation: the money moved, the headroom stays consumed. Idempotent. */
    suspend fun confirm(delegationId: UUID, reservationId: UUID, callerPartyId: CallerPartyId): SpendReservation

    /**
     * Gives the headroom back because the payment did not happen. Idempotent on an already-released
     * reservation, refused on a confirmed one.
     */
    suspend fun release(delegationId: UUID, reservationId: UUID, callerPartyId: CallerPartyId): SpendReservation
}

/** What one domestic-payment outcome did to its reservation (ADR-0249 D3 amendment, #16000). */
enum class PaymentOutcomeResult {
    CONFIRMED,
    RELEASED,

    /** A CONFIRMED reservation whose payment was RETURNED gave its headroom back. */
    RETURNED_RELEASED,

    /** The reservation already carried this outcome: a redelivery, or the edge settled it first. */
    ALREADY_SETTLED,

    /** The reservation is in the other terminal state, or is not a domestic-payment reservation. */
    CONFLICT,

    /** No such reservation under that delegation. */
    UNKNOWN_RESERVATION,
}

/**
 * Settles a domestic-payment reservation from the payment's reported outcome. A system path: no
 * caller party, because the outcome comes from the rail's own event stream, not from a customer.
 */
interface SettleSpendOnPaymentOutcomeUseCase {
    suspend fun applyPaymentOutcome(
        delegationId: UUID,
        reservationId: UUID,
        outcome: DomesticPaymentOutcome,
    ): PaymentOutcomeResult
}
