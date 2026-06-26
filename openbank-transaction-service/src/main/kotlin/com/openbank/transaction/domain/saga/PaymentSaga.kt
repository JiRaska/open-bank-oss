// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.domain.saga

import com.openbank.libs.domain.saga.SagaStateMachine
import com.openbank.libs.domain.saga.SagaTransitionPolicy
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Payment saga state machine.
 *
 * Happy path:  STARTED → PAYMENT_INITIATED → FUNDS_RESERVED → LEDGER_POSTING → FUNDS_CAPTURED → COMPLETED
 *               (incoming credit with no source account skips the fund legs: → LEDGER_POSTING → COMPLETED)
 * Compensation: any state → COMPENSATING → COMPENSATED
 * Terminal failure: FAILED (unrecoverable)
 */
data class PaymentSaga(
    val id: UUID,
    val transactionId: UUID,
    val state: SagaState,
    val idempotencyKey: String,
    val failureReason: String?,
    val compensationReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    fun transitionTo(newState: SagaState, clock: Clock): PaymentSaga {
        STATE_MACHINE.requireValid(state, newState) { from, to ->
            "Invalid saga transition: $from → $to for saga $id"
        }
        return copy(state = newState, updatedAt = Instant.now(clock))
    }

    fun fail(reason: String, clock: Clock): PaymentSaga =
        copy(state = SagaState.FAILED, failureReason = reason, updatedAt = Instant.now(clock))

    fun startCompensation(reason: String, clock: Clock): PaymentSaga =
        copy(state = SagaState.COMPENSATING, compensationReason = reason, updatedAt = Instant.now(clock))

    fun compensated(clock: Clock): PaymentSaga = copy(state = SagaState.COMPENSATED, updatedAt = Instant.now(clock))

    val isTerminal: Boolean
        get() = STATE_MACHINE.isTerminal(state)

    companion object {
        fun start(transactionId: UUID, idempotencyKey: String, clock: Clock): PaymentSaga {
            val now = Instant.now(clock)
            return PaymentSaga(
                id = UUID.randomUUID(),
                transactionId = transactionId,
                state = SagaState.STARTED,
                idempotencyKey = idempotencyKey,
                failureReason = null,
                compensationReason = null,
                createdAt = now,
                updatedAt = now,
                version = 0L,
            )
        }

        // Allowed transitions for the payment saga, validated by the shared
        // SagaStateMachine primitive (openbank-libs, ADR-0045). States with no outgoing
        // edge (COMPLETED / COMPENSATED / FAILED) are terminal.
        private val POLICY: SagaTransitionPolicy<SagaState> = SagaTransitionPolicy(
            mapOf(
                SagaState.STARTED to setOf(SagaState.PAYMENT_INITIATED, SagaState.COMPENSATING, SagaState.FAILED),
                SagaState.PAYMENT_INITIATED to setOf(
                    SagaState.FUNDS_RESERVED,
                    SagaState.LEDGER_POSTING,
                    SagaState.COMPENSATING,
                    SagaState.FAILED,
                ),
                SagaState.FUNDS_RESERVED to setOf(SagaState.LEDGER_POSTING, SagaState.COMPENSATING, SagaState.FAILED),
                SagaState.LEDGER_POSTING to setOf(
                    SagaState.FUNDS_CAPTURED,
                    SagaState.COMPLETED,
                    SagaState.COMPENSATING,
                    SagaState.FAILED,
                ),
                SagaState.FUNDS_CAPTURED to setOf(SagaState.COMPLETED, SagaState.COMPENSATING, SagaState.FAILED),
                SagaState.COMPENSATING to setOf(SagaState.COMPENSATED, SagaState.FAILED),
                SagaState.COMPLETED to emptySet(),
                SagaState.COMPENSATED to emptySet(),
                SagaState.FAILED to emptySet(),
            ),
        )

        private val STATE_MACHINE = SagaStateMachine(POLICY)

        fun isValidTransition(from: SagaState, to: SagaState): Boolean = STATE_MACHINE.isValid(from, to)
    }
}

enum class SagaState {
    STARTED,
    PAYMENT_INITIATED,
    FUNDS_RESERVED,
    LEDGER_POSTING,
    FUNDS_CAPTURED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
    FAILED,
}
