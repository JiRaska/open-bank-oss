// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.simulation.model

import com.openbank.transaction.domain.saga.PaymentSaga
import com.openbank.transaction.domain.saga.SagaState
import java.math.BigDecimal
import java.util.UUID

// Re-export so scenario/invariant code can import from one place.
typealias SimSagaState = SagaState

/**
 * Simulation wrapper around the real [PaymentSaga] domain aggregate from
 * `openbank-transaction-service` (ADR-0100 Layer 2). The domain object carries state-machine
 * correctness; this wrapper carries simulation bookkeeping fields that have no equivalent
 * in the production aggregate.
 */
data class SimPaymentSaga(
    val saga: PaymentSaga,
    val journalId: UUID? = null,
    val reservedAmount: BigDecimal? = null,
    val sourceAccount: AccountCurrency? = null,
) {
    val id: UUID get() = saga.id
    val state: SagaState get() = saga.state
    val isTerminal: Boolean get() = saga.isTerminal
}
