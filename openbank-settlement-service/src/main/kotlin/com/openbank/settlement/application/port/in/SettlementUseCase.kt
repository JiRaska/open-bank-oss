// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.port.`in`

import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.domain.model.validateSettlementAmount
import java.math.BigDecimal
import java.util.UUID

/**
 * Request to originate a new settlement between two customer accounts. [idempotencyKey] is a
 * caller-supplied dedup token: re-submitting the same key with the same instruction returns the
 * original settlement (no second debit/credit). A changed payer, payee, amount or currency is
 * rejected before workflow dispatch, including when a concurrent insert wins the key.
 */
data class OriginateSettlementCommand(
    val idempotencyKey: String,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: BigDecimal,
    val currency: String,
) {
    init {
        validateSettlementAmount(amount)
    }
}

interface SettlementUseCase {
    /**
     * Persist a new PENDING settlement and kick off its settlement (Temporal durable workflow
     * when enabled, else the legacy in-process saga). Returns the persisted settlement; the final
     * status (BOOKED / REJECTED) is reached asynchronously by the workflow.
     */
    suspend fun originate(command: OriginateSettlementCommand): Settlement

    suspend fun settle(settlementId: UUID): SettlementStatus
}
