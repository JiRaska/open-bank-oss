// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.application.port.out

import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The net-settlement leg of a settled clearing batch (ADR-0281), expressed in the clearing
 * domain's own terms and left for the adapter to turn into a balanced double-entry journal —
 * the same shape as interest-service's `LedgerPostingPort` (hand the ledger a journal; the
 * ledger is the single system of record for money movements).
 */
data class NetSettlementPosting(
    val batchId: UUID,
    val batchReference: String,
    val cycleId: String,
    /** Deterministic per batch — a redelivered command must collapse onto the one booked journal. */
    val idempotencyKey: String,
    val currency: String,
    val settlementAmount: BigDecimal,
    val valueDate: LocalDate,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "idempotencyKey cannot be blank" }
        require(currency.length == CURRENCY_CODE_LENGTH) { "currency must be ISO-4217: $currency" }
        require(settlementAmount.signum() > 0) {
            "a net-settlement leg has a positive amount: $settlementAmount"
        }
    }

    private companion object {
        const val CURRENCY_CODE_LENGTH = 3
    }
}

/**
 * Posts the net-settlement leg to `openbank-ledger-service`.
 *
 * Implementations MUST be idempotent on [NetSettlementPosting.idempotencyKey] so a consumer
 * retry after a crash (or a Kafka redelivery) collapses onto the already-booked journal instead
 * of double-settling the batch — the ledger enforces it, the key is what makes it deterministic.
 */
interface ClearingLedgerPostingPort {
    fun postNetSettlement(posting: NetSettlementPosting): Uni<Unit>
}
