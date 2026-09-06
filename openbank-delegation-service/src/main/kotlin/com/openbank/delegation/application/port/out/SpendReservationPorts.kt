// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.CountedSpend
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.SpendDecision
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.delegation.domain.model.SpendWindow
import com.openbank.libs.domain.event.DomainEvent
import java.time.OffsetDateTime
import java.util.UUID

/** What one reserve attempt produced. [Replayed] carries the reservation an earlier call created. */
sealed interface ReserveOutcome {
    data class Created(val reservation: SpendReservation) : ReserveOutcome
    data class Replayed(val reservation: SpendReservation) : ReserveOutcome
    data object IdempotencyConflict : ReserveOutcome
    data object StateStreamUnavailable : ReserveOutcome
    data class Refused(val decision: SpendDecision.Refused) : ReserveOutcome
}

/**
 * ADR-0249 D3 — the authoritative counter, and the one place its atomicity lives.
 *
 * The port takes a DECIDE callback rather than a plain "insert this row" method because the read
 * of the counters and the write that changes them must be one indivisible step. Splitting them
 * across two port calls would put the check-then-act race back exactly where reserve-before-move
 * exists to remove it. The domain keeps the arithmetic ([com.openbank.delegation.domain.model.SpendCeilings]);
 * the adapter keeps the transaction.
 */
interface SpendReservationRepository {

    /**
     * Count, decide and (if allowed) insert [candidate] — atomically.
     *
     * [decide] is invoked with the totals of every RESERVED and CONFIRMED reservation on this grant
     * inside [window], denominated in [candidate]'s currency. It is called at most once, and only
     * after the concurrency guarantee is in place.
     *
     * [auditEvent] is written to the outbox INSIDE the same transaction as the insert, so a
     * committed reservation always has its audit event and a rolled-back one never does
     * (ADR-0249 D4, issue #5728). It is built from the reservation that was actually created, and
     * is invoked ONLY on [ReserveOutcome.Created] — a replay created no state and so is not a
     * second reservation to audit, and a refusal created none at all.
     */
    suspend fun reserve(
        candidate: SpendReservation,
        window: SpendWindow,
        auditEvent: (SpendReservation) -> DomainEvent,
        decide: (DelegationGrant, CountedSpend) -> SpendDecision,
    ): ReserveOutcome

    suspend fun findById(grantId: UUID, reservationId: UUID): SpendReservation?

    /**
     * Move a reservation out of RESERVED under a compare-and-set on its current state, so a
     * confirm and a release racing on the same reservation cannot both land. Returns null when the
     * row was not in RESERVED — the caller decides whether that is a replay or a conflict.
     *
     * [auditEvent] is written to the outbox in the same transaction as the state change, and only
     * when the compare-and-set actually won: the losing side of a confirm/release race changed
     * nothing, so auditing it would record a settlement that never happened.
     */
    suspend fun settle(
        grantId: UUID,
        reservationId: UUID,
        target: SpendReservationState,
        settledAt: OffsetDateTime,
        auditEvent: (SpendReservation) -> DomainEvent,
    ): SpendReservation?
}
