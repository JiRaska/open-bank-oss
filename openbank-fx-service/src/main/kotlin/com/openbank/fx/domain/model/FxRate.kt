// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

enum class RateType { SPOT, FORWARD, INDICATIVE, INTERBANK }
enum class RateSource { ECB, REUTERS, BLOOMBERG, INTERNAL, CNB }

data class FxRate(
    val id: UUID,
    val baseCurrency: String,
    val quoteCurrency: String,
    val bidRate: BigDecimal,
    val askRate: BigDecimal,
    val rateType: RateType,
    val source: RateSource,
    val validFrom: Instant,
    val validTo: Instant,
    val createdAt: Instant,
) {
    val pair: String get() = "$baseCurrency/$quoteCurrency"
    val midRate: BigDecimal get() = (bidRate + askRate).divide(BigDecimal.TWO)
    val spread: BigDecimal get() = askRate - bidRate
    fun isValid(at: Instant = Instant.EPOCH) = at.isAfter(validFrom) && at.isBefore(validTo)
}

data class FxConversion(
    val id: UUID,
    val idempotencyKey: String,
    val partyId: UUID,
    val accountId: UUID?,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmountMinorUnits: Long,
    val toAmountMinorUnits: Long,
    val appliedRate: BigDecimal,
    val feeMinorUnits: Long,
    val rateId: UUID,
    val status: FxConversionStatus,
    val createdAt: Instant,
    val settledAt: Instant?,
)

enum class FxConversionStatus { PENDING, SETTLED, FAILED, REVERSED }

/**
 * Pure conversion arithmetic (issue #469 item 3 — ADR-0011 property testing). Extracted out of
 * [FxService][com.openbank.fx.application.usecase.FxService].convert() so the margin math is
 * callable from a property test without instantiating the use case and its 8 mocked ports.
 */
object FxConversionMath {
    private val FEE_RATE = BigDecimal("0.005")

    /** `fromAmount * appliedRate`, rounded HALF_UP to whole minor units. */
    fun convertedAmountMinorUnits(fromAmountMinorUnits: Long, appliedRate: BigDecimal): Long =
        BigDecimal(fromAmountMinorUnits).multiply(appliedRate).setScale(0, RoundingMode.HALF_UP).toLong()

    /** The bank's 0.5% margin on the source amount, rounded HALF_UP to whole minor units. */
    fun feeMinorUnits(fromAmountMinorUnits: Long): Long =
        BigDecimal(fromAmountMinorUnits).multiply(FEE_RATE).setScale(0, RoundingMode.HALF_UP).toLong()
}
