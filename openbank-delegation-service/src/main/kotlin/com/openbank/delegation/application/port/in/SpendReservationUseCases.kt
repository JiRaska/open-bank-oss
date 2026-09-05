// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.`in`

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
