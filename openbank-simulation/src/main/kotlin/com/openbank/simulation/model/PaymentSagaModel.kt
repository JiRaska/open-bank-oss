// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.transaction.domain.saga.SagaState
import java.math.BigDecimal
import java.util.UUID

// Re-export so scenario/invariant code can import from one place.
typealias SimSagaState = SagaState

/**
 * Simulation-layer payment saga record (ADR-0100 Layer 2, ADR-0120 Phase 5).
 *
 * After the production `PaymentSagaOrchestrator` was retired in favour of Temporal, this class no
 * longer wraps the domain aggregate — it carries only the fields the simulation needs: identity,
 * current state, and bookkeeping (journal id, hold amount, source account).
 */
data class SimPaymentSaga(
    val id: UUID,
    val transactionId: UUID,
    val state: SagaState,
    val journalId: UUID? = null,
    val reservedAmount: BigDecimal? = null,
    val sourceAccount: AccountCurrency? = null,
) {
    val isTerminal: Boolean
        get() = state in TERMINAL_STATES

    fun transitionTo(newState: SagaState): SimPaymentSaga = copy(state = newState)

    companion object {
        private val TERMINAL_STATES = setOf(SagaState.COMPLETED, SagaState.COMPENSATED, SagaState.FAILED)
    }
}
