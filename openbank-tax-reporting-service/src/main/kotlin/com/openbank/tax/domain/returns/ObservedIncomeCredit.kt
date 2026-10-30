// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.returns

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One interest credit the fleet has announced on `openbank.interest.accrual.event` as
 * `interest.withholding.recorded.v1` (ADR-0033 §F), recorded verbatim for CRS/FATCA reporting
 * assembly (ADR-0340 B).
 *
 * Stored keyed by [withholdingId] so a redelivery (Kafka is at-least-once) is a no-op, the same
 * idempotency shape as the §38d observed remittance. What is stored is the GROSS interest credited
 * to the account for the accrual period — CRS reports gross income, never the withholding decision
 * (ADR-0340 E: reporting reads income events, never the tax decision).
 */
data class ObservedIncomeCredit(
    val withholdingId: UUID,
    val capitalizationId: UUID,
    val accountId: UUID,
    val productId: UUID,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val currency: String,
    val grossAmount: BigDecimal,
    val netAmount: BigDecimal,
    val occurredAt: Instant,
    val observedAt: Instant,
) {
    init {
        require(grossAmount.signum() >= 0) { "grossAmount must not be negative: $grossAmount" }
        require(netAmount.signum() >= 0) { "netAmount must not be negative: $netAmount" }
        require(currency.length == CURRENCY_CODE_LENGTH) { "currency must be an ISO-4217 code: $currency" }
        require(!periodTo.isBefore(periodFrom)) { "periodTo $periodTo precedes periodFrom $periodFrom" }
    }

    companion object {
        private const val CURRENCY_CODE_LENGTH = 3
    }
}

/** Gross interest credited to one account in one currency across the queried window. */
data class IncomeCreditTotals(val accountId: UUID, val currency: String, val gross: BigDecimal)
