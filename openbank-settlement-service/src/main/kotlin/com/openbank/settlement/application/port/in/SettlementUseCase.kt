// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.settlement.application.port.`in`

import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import java.math.BigDecimal
import java.util.UUID

/**
 * Request to originate a new settlement between two customer accounts. [idempotencyKey] is a
 * caller-supplied dedup token: re-submitting the same key is a no-op that returns the original
 * settlement (no second debit/credit), so a client retry can never double-settle.
 */
data class OriginateSettlementCommand(
    val idempotencyKey: String,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: BigDecimal,
    val currency: String,
)

interface SettlementUseCase {
    /**
     * Persist a new PENDING settlement and kick off its settlement (Temporal durable workflow
     * when enabled, else the legacy in-process saga). Returns the persisted settlement; the final
     * status (BOOKED / REJECTED) is reached asynchronously by the workflow.
     */
    suspend fun originate(command: OriginateSettlementCommand): Settlement

    suspend fun settle(settlementId: UUID): SettlementStatus
}
